package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.VersionContract
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.ConsoleReporter
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.JsonReporter
import com.salyvn.shadersmith.report.SarifReporter
import com.salyvn.shadersmith.report.Severity
import java.nio.file.Files

/**
 * Checks a pack against one version, or against every version in a range and reports the window in
 * which it is actually valid.
 */
class CheckCommand : ShadersmithCommand(name = "check") {

    override fun help(context: Context) = "Check a pack's shaders against a Minecraft version's contract."

    private val pack by argument("pack", help = "Pack directory or zip").path(mustExist = true)

    private val mc by option("--mc", help = "Target version, e.g. 26.2 or 1.21.9, or a pack format number")

    private val range by option("--mc-range", help = "Check every known version in a range, e.g. 1.21.6..26.3-pre-1")

    private val sarif by option("--sarif", help = "Also write findings as SARIF to this path").path()

    private val json by option("--json", help = "Print findings as JSON instead of text").flag()

    private val warningsAsErrors by option("--strict", help = "Exit 1 on warnings too").flag()

    override fun execute() {
        val source = PackSource.read(pack)
        val versions = targets()
        val findings = versions.flatMap { contract -> ContractChecker(contract).check(source) }

        if (json) {
            echo(JsonReporter.render(findings).trimEnd())
        } else {
            ConsoleReporter.lines(findings, pack.fileName.toString()).forEach { echo(it) }
            if (versions.size > 1) echo(windowSummary(versions, findings))
        }
        sarif?.let { target ->
            target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(target, SarifReporter.render(findings))
            if (!json) echo("SARIF written to $target")
        }

        val failing = findings.any { it.severity == Severity.ERROR || (warningsAsErrors && it.severity == Severity.WARNING) }
        if (failing) throw ProgramResult(CHECK_FAILED)
    }

    private fun targets(): List<VersionContract> {
        if (mc != null && range != null) fail("Give either --mc or --mc-range, not both.")
        range?.let { text ->
            val parts = text.split("..")
            if (parts.size != 2) fail("--mc-range wants 'from..to', like 1.21.6..26.2")
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
