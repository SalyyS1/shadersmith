package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.shadersmith.contract.PackMetaReader
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.GlslStruct
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.pressure.FilePressureReport
import com.salyvn.shadersmith.pressure.RegisterPressureAnalyzer
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.SarifReporter
import com.salyvn.shadersmith.report.Severity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Audits GLSL shader files or resource packs for dead uniforms/samplers (`SM059`), temporary vector
 * register live-range pressure & scratch array spills (`SM077`), divergent fragment derivatives (`SM078`),
 * and unread local assignments (`SM079`).
 */
class PressureCommand : ShadersmithCommand(name = "pressure") {

    override fun help(context: Context) =
        "Analyze GLSL dead uniforms, register live-range pressure, wave occupancy, and divergent derivatives."

    private val target by argument("target", help = "GLSL shader file (.vsh/.fsh/.csh/.glsl), pack directory, or .zip")
        .path(mustExist = true)

    private val mc by option("--mc", help = "Target Minecraft version for vanilla block contracts and overlays")

    private val maxTemps by option("--max-temps", help = "Maximum live vec4 temporary register budget (default: 32)")
        .int()
        .default(RegisterPressureAnalyzer.DEFAULT_MAX_VEC4_TEMPS)

    private val json by option("--json", help = "Emit register pressure reports as JSON").flag()

    private val sarif by option("--sarif", help = "Write findings as OASIS SARIF v2.1.0 to this path").path()

    private val strict by option("--strict", help = "Exit 1 on warnings too").flag()

    override fun execute() {
        require(maxTemps > 0) { "--max-temps must be a positive integer." }
        val contract = dataset.resolve(mc ?: dataset.versions.last().id)
        val vanillaBlocks = contract.uniformBlocks.keys
        val reports = mutableListOf<FilePressureReport>()
        val findings = mutableListOf<Finding>()

        if (target.isRegularFile() && target.extension.lowercase() != "zip") {
            val text = Files.readString(target, StandardCharsets.UTF_8)
            val file = PackFile(relative = target.fileName.toString(), text = text, physicalRelative = target.toString())
            val src = GlslSource(text)
            reports += RegisterPressureAnalyzer.checkFile(
                file = file,
                source = src,
                versionId = contract.id,
                vanillaUniformBlocks = vanillaBlocks,
                maxVec4Temps = maxTemps,
                out = findings,
            )
        } else {
            val pack = PackSource.read(target)
            val overlays = pack.packMeta?.let { PackMetaReader.read(it.text)?.overlays } ?: emptyList()
            val effective = pack.effectiveFiles(contract.packFormat, overlays)
            val glslByPath = effective.filter { it.isShaderSource }.associate { it.relative to GlslSource(it.text) }

            for (file in effective.filter { it.isShaderSource }) {
                val (transStructs, transExec) = collectTransitiveStructsAndExec(file.relative, glslByPath)
                reports += RegisterPressureAnalyzer.checkFile(
                    file = file,
                    source = glslByPath.getValue(file.relative),
                    versionId = contract.id,
                    vanillaUniformBlocks = vanillaBlocks,
                    transitiveStructs = transStructs,
                    transitiveExecutableCode = transExec,
                    maxVec4Temps = maxTemps,
                    out = findings,
                )
            }
        }

        if (json) {
            echo(PRETTY.encodeToString(reports))
        } else if (reports.isEmpty()) {
            echo("No shader files found in $target.")
        } else {
            reports.forEach { rep ->
                echo("== ${rep.file} (${rep.stage})  peak: ${rep.peakVec4Registers}/${rep.maxVec4Budget} vec4 (${rep.peakScalarComponents} scalars, ~${rep.estimatedOccupancyPct}% occupancy)")
                if (rep.deadSymbols.isNotEmpty()) {
                    echo("   Dead symbols: ${rep.deadSymbols.joinToString(", ")}")
                }
                rep.functions.forEach { fn ->
                    val liveSample = if (fn.liveAtPeak.isEmpty()) "none" else fn.liveAtPeak.joinToString(", ")
                    echo("   fn ${fn.functionName}() [line ${fn.line}]: peak ${fn.peakVec4Registers} vec4 at line ${fn.peakLine} (loops: ${fn.loopCount}, live: $liveSample)")
                }
                rep.findings.forEach { f ->
                    val loc = f.line?.let { ":${it}" } ?: ""
                    echo("      ${f.severity.padEnd(7)} ${f.rule} ${f.file}$loc: ${f.message}")
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

    companion object {
        private val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        internal fun collectTransitiveStructsAndExec(
            rootLogicalPath: String,
            glslByPath: Map<String, GlslSource>,
        ): Pair<Map<String, GlslStruct>, String> {
            val structs = linkedMapOf<String, GlslStruct>()
            val exec = StringBuilder()
            val visited = mutableSetOf(rootLogicalPath)

            fun resolve(fromPath: String, incTarget: String): String? {
                if (incTarget.contains(':')) {
                    val ns = incTarget.substringBefore(':')
                    val rel = incTarget.substringAfter(':')
                    return "assets/$ns/shaders/include/$rel".takeIf { it in glslByPath }
                }
                val sibling = "${fromPath.substringBeforeLast('/')}/$incTarget"
                if (sibling in glslByPath) return sibling
                return "assets/minecraft/shaders/include/$incTarget".takeIf { it in glslByPath }
            }

            fun dfs(current: String) {
                val src = glslByPath[current] ?: return
                for (inc in src.includes) {
                    val next = resolve(current, inc.target) ?: continue
                    if (visited.add(next)) {
                        val incSrc = glslByPath[next] ?: continue
                        incSrc.structs.forEach { structs[it.name] = it }
                        exec.append(incSrc.executableCode).append('\n')
                        dfs(next)
                    }
                }
            }

            dfs(rootLogicalPath)
            return structs to exec.toString()
        }
    }
}
