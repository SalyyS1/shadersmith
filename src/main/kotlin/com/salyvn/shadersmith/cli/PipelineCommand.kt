package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.shadersmith.contract.PackMetaReader
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.pipeline.PipelineCompiler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Compiles and displays Minecraft 26.3+ core shader pipeline state permutations (`OPAQUE`, `ALPHA_CUTOUT`,
 * `PER_FACE_LIGHTING`, `EMISSIVE_GLINT`, `OIT_DEPTH_BOUNDS`, `OIT_TRANSMITTANCE`, `OIT_ACCUMULATE`),
 * vertex attributes, cross-stage varyings, samplers, uniform blocks, and SSBO bindings.
 */
class PipelineCommand : ShadersmithCommand(name = "pipeline") {

    override fun help(context: Context) =
        "Compile core shader pipeline state permutations, stage bindings, and Wavelet OIT attachments."

    private val pack by argument("pack", help = "Pack directory or zip").path(mustExist = true)

    private val mc by option("--mc", help = "Target version (default: newest known, e.g. 26.3)")

    private val program by option("--program", help = "Filter by core program name (e.g. entity or minecraft:entity)")

    private val json by option("--json", help = "Emit compiled pipeline states as JSON").flag()

    override fun execute() {
        val contract = dataset.resolve(mc ?: dataset.versions.last().id)
        val source = PackSource.read(pack)
        val overlays = source.packMeta?.let { PackMetaReader.read(it.text)?.overlays } ?: emptyList()
        val effective = source.effectiveFiles(contract.packFormat, overlays)
        val glslByPath = effective.filter { it.isShaderSource }.associate { it.relative to GlslSource(it.text) }

        val compiled = PipelineCompiler.compilePrograms(effective, glslByPath, contract, program)
        if (json) {
            echo(PRETTY.encodeToString(compiled))
            return
        }
        if (compiled.isEmpty()) {
            echo("No matching core shader programs found in $pack on ${contract.id}.")
            return
        }

        compiled.forEach { prog ->
            val oitLabel = if (prog.usesWaveletOit) " [Wavelet OIT]" else ""
            echo("== ${prog.namespace}:core/${prog.program} on ${prog.version}$oitLabel")
            echo("   vsh: ${prog.vertexShader ?: "(vanilla)"}   fsh: ${prog.fragmentShader ?: "(vanilla)"}")
            prog.permutations.forEach { perm ->
                val defs = if (perm.defines.isEmpty()) "(none)" else perm.defines.joinToString(", ")
                echo("   -- state ${perm.name}  defines: $defs")
                if (perm.vertexInputs.isNotEmpty()) {
                    echo("      in:      " + perm.vertexInputs.joinToString(", ") { "@${it.location} ${it.type} ${it.name}" })
                }
                if (perm.stageVaryings.isNotEmpty()) {
                    echo("      varying: " + perm.stageVaryings.joinToString(", ") { "@${it.location} ${it.interpolation} ${it.type} ${it.name}" })
                }
                if (perm.fragmentOutputs.isNotEmpty()) {
                    echo("      out:     " + perm.fragmentOutputs.joinToString(", ") { "@${it.location} ${it.type} ${it.name}" })
                }
                if (perm.samplers.isNotEmpty()) {
                    echo("      samplers: ${perm.samplers.joinToString(", ")}")
                }
                if (perm.storageBuffers.isNotEmpty()) {
                    echo("      ssbos:    ${perm.storageBuffers.joinToString(", ")}")
                }
            }
        }
    }

    companion object {
        private val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
