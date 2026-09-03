package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Prints what the tool knows about a version, which is also how you check the tool against the game.
 * `--json` gives the dataset row unchanged.
 */
class ContractCommand : ShadersmithCommand(name = "contract") {

    override fun help(context: Context) = "Show the shader contract for a version: files, blocks, dialect."

    private val mc by option("--mc", help = "Version id or pack format (default: newest known)")

    private val json by option("--json", help = "Print the dataset row as JSON").flag()

    private val list by option("--list", help = "List the versions in the dataset").flag()

    override fun execute() {
        if (list) {
            dataset.versions.forEach { v ->
                echo("${v.id.padEnd(11)} format ${v.packFormat.toString().padEnd(3)} glsl ${v.glslVersion}  ${v.directiveSpelling.padEnd(11)} post ${v.postEffectSchema}")
            }
            return
        }
        val contract = dataset.resolve(mc ?: dataset.versions.last().id)
        if (json) {
            echo(Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(contract))
            return
        }
        echo("${contract.id}  (resource pack format ${contract.packFormat}${if (contract.packFormatMinor > 0) ".${contract.packFormatMinor}" else ""})")
        echo("   glsl            #version ${contract.glslVersion}")
        echo("   include         ${contract.directiveSpelling} <minecraft:name.glsl>${if (contract.includesCarryVersion) "" else "   (includes carry no #version)"}")
        echo("   core programs   ${contract.coreFiles.size} files${if (contract.coreProgramJson) ", with .json definitions" else ", GLSL only"}")
        echo("   includes        ${contract.includeFiles.joinToString(", ")}")
        echo("   post effects    ${contract.postEffects.joinToString(", ")}   (schema: ${contract.postEffectSchema}, vertex: ${contract.postPassVertexShader})")
        echo("   uniform blocks  ${contract.uniformBlocks.size}")
        contract.uniformBlocks.forEach { (name, members) ->
            echo("      $name { ${members.joinToString("; ") { "${it.type} ${it.name}" }} }")
        }
        echo("")
        echo("Dataset generated ${dataset.generated} from ${dataset.source}.")
    }
}
