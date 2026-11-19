package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.shadersmith.contract.PackMetaReader
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.SarifReporter
import com.salyvn.shadersmith.report.Severity
import com.salyvn.shadersmith.vram.PostEffectVramReport
import com.salyvn.shadersmith.vram.VramCalculator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Computes exact post-effect render-target VRAM allocations, 4K scaling footprints, frame memory
 * bandwidth, transient render-target live-interval aliasing savings, and ping-pong hazards (`SM080`..`SM082`).
 */
class VramCommand : ShadersmithCommand(name = "vram") {

    override fun help(context: Context) =
        "Calculate post-effect render-target VRAM footprints, bandwidth, aliasing pools, and ping-pong hazards."

    private val target by argument("target", help = "Post-effect .json file, pack directory, or .zip archive")
        .path(mustExist = true)

    private val mc by option("--mc", help = "Target Minecraft version for overlay resolution (default: newest known)")

    private val width by option("--width", help = "Viewport width in pixels (default: 1920)")
        .int()
        .default(VramCalculator.DEFAULT_WIDTH)

    private val height by option("--height", help = "Viewport height in pixels (default: 1080)")
        .int()
        .default(VramCalculator.DEFAULT_HEIGHT)

    private val fps by option("--fps", help = "Target frame rate in Hz for bandwidth estimation (default: 60)")
        .int()
        .default(VramCalculator.DEFAULT_FPS)

    private val budgetMb by option("--budget-mb", help = "Maximum per-chain VRAM budget in MiB (default: 128.0)")
        .double()
        .default(VramCalculator.DEFAULT_BUDGET_MB)

    private val json by option("--json", help = "Emit VRAM and aliasing reports as JSON").flag()

    private val sarif by option("--sarif", help = "Write findings as OASIS SARIF v2.1.0 to this path").path()

    private val strict by option("--strict", help = "Exit 1 on warnings too").flag()

    override fun execute() {
        require(width in 1..16384 && height in 1..16384) { "--width and --height must be within 1..16384." }
        require(fps > 0) { "--fps must be positive." }
        require(budgetMb > 0.0) { "--budget-mb must be positive." }

        val contract = dataset.resolve(mc ?: dataset.versions.last().id)
        val reports = mutableListOf<PostEffectVramReport>()
        val findings = mutableListOf<Finding>()

        if (target.isRegularFile() && target.extension.lowercase() == "json") {
            val text = Files.readString(target, StandardCharsets.UTF_8)
            val file = PackFile(relative = target.fileName.toString(), text = text, physicalRelative = target.toString())
            val rep = VramCalculator.analyzePostEffect(
                file = file,
                versionId = contract.id,
                viewportWidth = width,
                viewportHeight = height,
                fps = fps,
                budgetMb = budgetMb,
                out = findings,
            ) ?: fail("Could not parse post-effect JSON in $target.")
            reports += rep
        } else {
            val pack = PackSource.read(target)
            val overlays = pack.packMeta?.let { PackMetaReader.read(it.text)?.overlays } ?: emptyList()
            val effective = pack.effectiveFiles(contract.packFormat, overlays)
            effective.filter { it.isPostEffectDefinition }.forEach { file ->
                VramCalculator.analyzePostEffect(
                    file = file,
                    versionId = contract.id,
                    viewportWidth = width,
                    viewportHeight = height,
                    fps = fps,
                    budgetMb = budgetMb,
                    out = findings,
                )?.let { reports += it }
            }
        }

        if (json) {
            echo(PRETTY.encodeToString(reports))
        } else if (reports.isEmpty()) {
            echo("No post-effect definitions found in $target.")
        } else {
            reports.forEach { rep ->
                echo("== ${rep.file} (${rep.passCount} pass(es) @ ${rep.viewportWidth}x${rep.viewportHeight}, ${rep.fps} FPS)")
                echo(
                    "   VRAM: ${fmtMb(rep.allocatedVramBytes)} allocated | ${fmtMb(rep.aliasedPeakVramBytes)} aliased minimum " +
                        "(reclaimable: ${fmtMb(rep.reclaimableAliasBytes)}) | 4K: ${fmtMb(rep.allocated4kVramBytes)}",
                )
                echo(
                    "   Bandwidth: ${rep.estimatedBandwidthGbPerSec} GB/s " +
                        "(read ${fmtMb(rep.bytesReadPerFrame)}/frame, write ${fmtMb(rep.bytesWrittenPerFrame)}/frame)",
                )
                rep.targets.forEach { t ->
                    val scaleTag = if (t.viewportScaled) "viewport" else "fixed"
                    val life = "W:${t.firstWritePass ?: "-"}..R:${t.lastReadPass ?: "-"}"
                    val pers = if (t.persistent) ", persistent" else ""
                    echo(
                        "      target ${t.name.padEnd(18)} ${t.format.padEnd(14)} ${t.width}x${t.height} ($scaleTag$pers)  " +
                            "${fmtMb(t.allocatedBytes).padEnd(10)} [$life, pool #${t.aliasSlot}]",
                    )
                }
                rep.aliasPools.filter { it.targets.size > 1 }.forEach { p ->
                    echo("   Alias opportunity (pool #${p.slot}, ${p.format} ${p.width}x${p.height}): reuse across ${p.targets.joinToString(" -> ")}")
                }
                rep.findings.forEach { f ->
                    echo("      ${f.severity.padEnd(7)} ${f.rule} ${f.file}: ${f.message}")
                }
            }
        }

        sarif?.let { sPath ->
            sPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(sPath, SarifReporter.render(findings))
            if (!json) echo("SARIF written to $sPath")
        }

        val failing = findings.any { it.severity == Severity.ERROR || (strict && it.severity == Severity.WARNING) }
        if (failing) throw ProgramResult(CHECK_FAILED)
    }

    private fun fmtMb(bytes: Long): String =
        String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0))

    companion object {
        private val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
