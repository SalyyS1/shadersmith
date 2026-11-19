package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.shadersmith.contract.PackMetaReader
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.ConsoleReporter
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.SarifReporter
import com.salyvn.shadersmith.report.Severity
import com.salyvn.shadersmith.spirv.SpirvAuditReport
import com.salyvn.shadersmith.spirv.SpirvFinding
import com.salyvn.shadersmith.spirv.SpirvValidator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Validates shaders against Vulkan / Metal SPIR-V intermediate representation cross-compilation contracts,
 * descriptor binding allocations, and stage interface layout locations.
 */
class SpirvCommand : ShadersmithCommand(name = "spirv") {

    override fun help(context: Context) =
        "Audit shader GLSL for Vulkan/Metal SPIR-V intermediate representation cross-compilation readiness."

    private val target by argument("target", help = "Shader file, pack directory, or .zip archive").path(mustExist = true)

    private val mc by option("--mc", help = "Target Minecraft version (default: newest known, e.g. 26.3)")

    private val json by option("--json", help = "Emit audit report as JSON").flag()

    private val sarif by option("--sarif", help = "Write findings as OASIS SARIF v2.1.0 to this path").path()

    private val strict by option("--strict", help = "Exit 1 on warnings too").flag()

    override fun execute() {
        val contract = dataset.resolve(mc ?: dataset.versions.last().id)
        val reports = mutableListOf<SpirvAuditReport>()
        val allFindings = mutableListOf<SpirvFinding>()

        if (target.isRegularFile() && (target.fileName.toString().endsWith(".vsh") ||
                target.fileName.toString().endsWith(".fsh") ||
                target.fileName.toString().endsWith(".csh") ||
                target.fileName.toString().endsWith(".glsl"))
        ) {
            val text = Files.readString(target)
            val file = PackFile(relative = target.fileName.toString(), text = text, physicalRelative = target.toString())
            val src = GlslSource(text)
            val report = SpirvValidator.auditFile(file, src, contract.id)
            reports += report
            allFindings.addAll(report.findings)
        } else {
            val source = PackSource.read(target)
            val overlays = source.packMeta?.let { PackMetaReader.read(it.text)?.overlays } ?: emptyList()
            val effective = source.effectiveFiles(contract.packFormat, overlays)
            val shaderFiles = effective.filter { it.isShaderSource }

            for (file in shaderFiles) {
                val src = GlslSource(file.text)
                val report = SpirvValidator.auditFile(file, src, contract.id)
                reports += report
                allFindings.addAll(report.findings)
            }
        }

        if (json) {
            echo(PRETTY.encodeToString(reports))
        } else {
            if (reports.isEmpty()) {
                echo("No shader files found in $target.")
                return
            }

            reports.forEach { rep ->
                val status = if (rep.isSpirvCompliant) "COMPLIANT" else "NON-COMPLIANT"
                echo("== ${rep.file} [$status]")
                if (rep.bindings.isNotEmpty()) {
                    echo("   Descriptor Set 0 Bindings:")
                    rep.bindings.forEach { b ->
                        echo("      binding = ${b.binding.toString().padEnd(2)}  ${b.kind.padEnd(14)}  ${b.type.padEnd(12)}  ${b.name} (${b.stage})")
                    }
                }
                if (rep.interfaces.isNotEmpty()) {
                    echo("   Interface Locations:")
                    rep.interfaces.forEach { i ->
                        echo("      location = ${i.location.toString().padEnd(2)}  ${i.direction.padEnd(3)}  ${i.type.padEnd(10)}  ${i.name} [${i.interpolation}]")
                    }
                }
                if (rep.findings.isNotEmpty()) {
                    echo("   Diagnostics:")
                    rep.findings.forEach { f ->
                        val loc = f.line?.let { ":$it" } ?: ""
                        echo("      ${f.severity.padEnd(7)} ${f.rule} ${f.file}$loc: ${f.message}")
                    }
                }
            }
        }

        sarif?.let { sPath ->
            sPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val converted = allFindings.map {
                val sev = when (it.severity) {
                    "error" -> Severity.ERROR
                    "warning" -> Severity.WARNING
                    else -> Severity.NOTE
                }
                Finding(it.rule, sev, it.version, it.file, it.line, it.message)
            }
            Files.writeString(sPath, SarifReporter.render(converted))
            if (!json) echo("SARIF written to $sPath")
        }

        val hasErrors = allFindings.any { it.severity == "error" || (strict && it.severity == "warning") }
        if (hasErrors) throw ProgramResult(CHECK_FAILED)
    }

    companion object {
        private val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
