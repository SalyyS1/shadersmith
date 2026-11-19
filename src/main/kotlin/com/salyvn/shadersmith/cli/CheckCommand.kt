package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.PackMetaReader
import com.salyvn.shadersmith.contract.VersionContract
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.pressure.RegisterPressureAnalyzer
import com.salyvn.shadersmith.report.ConsoleReporter
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.JsonReporter
import com.salyvn.shadersmith.report.SarifReporter
import com.salyvn.shadersmith.report.Severity
import com.salyvn.shadersmith.spirv.SpirvValidator
import java.nio.file.Files

/**
 * Checks a pack against one version, or against every version in a range and reports the window in
 * which it is actually valid.
 */
class CheckCommand : ShadersmithCommand(name = "check") {

    override fun help(context: Context) = "Check a pack's shaders against a Minecraft version's contract."

    private val pack by argument("pack", help = "Pack directory or zip").path(mustExist = true)

    private val mc by option("--mc", help = "Target version, e.g. 26.3 or 1.21.9, or a pack format number")

    private val range by option("--mc-range", help = "Check every known version in a range, e.g. 1.21.6..26.3")

    private val format by option("--format", help = "Stdout output format: text, json, or sarif")
        .choice("text", "json", "sarif")

    private val sarif by option("--sarif", help = "Also write findings as OASIS SARIF v2.1.0 to this path").path()

    private val json by option("--json", help = "Print findings as JSON instead of text").flag()

    private val warningsAsErrors by option("--strict", help = "Exit 1 on warnings too").flag()

    private val spirv by option("--spirv", help = "Also enforce Vulkan / Metal SPIR-V intermediate representation contracts").flag()

    private val pressure by option("--pressure", help = "Also run dead-uniform, register live-range pressure, and divergent derivative analysis").flag()

    private val maxTemps by option("--max-temps", help = "Maximum live vec4 temporary register budget for --pressure (default: 32)")
        .int()
        .default(RegisterPressureAnalyzer.DEFAULT_MAX_VEC4_TEMPS)

    override fun execute() {
        require(maxTemps > 0) { "--max-temps must be a positive integer." }
        if (json && format != null && format != "json") {
            fail("Cannot combine --json with --format $format.")
        }
        val effectiveFormat = when {
            json -> "json"
            format != null -> format!!
            else -> "text"
        }

        val source = PackSource.read(pack)
        val versions = targets()
        val findings = versions.flatMap { contract ->
            val baseFindings = ContractChecker(contract).check(source).toMutableList()
            if (spirv || pressure) {
                val overlays = source.packMeta?.let { PackMetaReader.read(it.text)?.overlays } ?: emptyList()
                val effective = source.effectiveFiles(contract.packFormat, overlays)
                val glslByPath = effective.filter { it.isShaderSource }.associate { it.relative to GlslSource(it.text) }
                effective.filter { it.isShaderSource }.forEach { file ->
                    val src = glslByPath.getValue(file.relative)
                    if (spirv) {
                        SpirvValidator.checkFile(file, src, contract.id, baseFindings)
                    }
                    if (pressure) {
                        val (transStructs, transExec) = PressureCommand.collectTransitiveStructsAndExec(file.relative, glslByPath)
                        RegisterPressureAnalyzer.checkFile(
                            file = file,
                            source = src,
                            versionId = contract.id,
                            vanillaUniformBlocks = contract.uniformBlocks.keys,
                            transitiveStructs = transStructs,
                            transitiveExecutableCode = transExec,
                            maxVec4Temps = maxTemps,
                            out = baseFindings,
                        )
                    }
                }
            }
            baseFindings.sortedWith(compareBy({ it.severity.ordinal }, { it.file }, { it.line ?: 0 }))
        }

        when (effectiveFormat) {
            "json" -> echo(JsonReporter.render(findings).trimEnd())
            "sarif" -> echo(SarifReporter.render(findings).trimEnd())
            else -> {
                ConsoleReporter.lines(findings, pack.fileName.toString()).forEach { echo(it) }
                if (versions.size > 1) echo(windowSummary(versions, findings))
            }
        }
        sarif?.let { target ->
            target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(target, SarifReporter.render(findings))
            if (effectiveFormat == "text") echo("SARIF written to $target")
        }

        val failing = findings.any { it.severity == Severity.ERROR || (warningsAsErrors && it.severity == Severity.WARNING) }
        if (failing) throw ProgramResult(CHECK_FAILED)
    }

    private fun targets(): List<VersionContract> {
        if (mc != null && range != null) fail("Give either --mc or --mc-range, not both.")
        range?.let { text ->
            val parts = text.split("..")
            if (parts.size != 2) fail("--mc-range wants 'from..to', like 1.21.6..26.3")
            return dataset.range(dataset.resolve(parts[0].trim()), dataset.resolve(parts[1].trim()))
        }
        return listOf(dataset.resolve(mc ?: dataset.versions.last().id))
    }

    /** Which versions the pack passes on without an error, which is the honest `pack.mcmeta` range. */
    private fun windowSummary(versions: List<VersionContract>, findings: List<Finding>): String {
        val clean = versions.filter { version -> findings.none { it.version == version.id && it.severity == Severity.ERROR } }
        if (clean.isEmpty()) return "\nThe pack has errors on every version checked."
        val runs = mutableListOf<MutableList<VersionContract>>()
        versions.forEach { version ->
            if (version in clean) {
                if (runs.isEmpty() || runs.last().last() != versions[versions.indexOf(version) - 1]) runs += mutableListOf(version)
                else runs.last() += version
            }
        }
        return buildString {
            append("\nValid without errors on: ")
            append(runs.joinToString("; ") { run ->
                if (run.size == 1) run.first().id else "${run.first().id}..${run.last().id}"
            })
            val widest = runs.maxByOrNull { it.size }!!
            append("\npack.mcmeta for the widest window: min_format ${widest.first().packFormat}, max_format ${widest.last().packFormat}")
        }
    }
}
