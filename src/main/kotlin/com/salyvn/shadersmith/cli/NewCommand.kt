package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.shadersmith.scaffold.PostEffectScaffold
import com.salyvn.shadersmith.scaffold.UniformSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Writes a post effect that loads on the target version, with its uniforms declared in both the JSON and
 * the GLSL so the two cannot disagree.
 */
class NewCommand : ShadersmithCommand(name = "new") {

    override fun help(context: Context) = "Scaffold a post effect: definition, fragment shader, commands."

    private val id by argument("id", help = "The effect's path, e.g. vignette or fx/blood")

    private val namespace by option("--ns", help = "Resource namespace the effect lives in").required()

    private val mc by option("--mc", help = "Target version (default: newest known)")

    private val uniforms by option(
        "--uniform",
        help = "A uniform as Name:type=value, repeatable. Types: float int vec2 vec3 vec4",
    ).multiple()

    private val alwaysOn by option(
        "--always-on",
        help = "Also write minecraft:end_of_frame so the effect runs whenever the pack is loaded (26.3+)",
    ).flag()

    private val out by option("--out", help = "Pack directory to write into (default: pack)").path()

    private val force by option("--force", help = "Overwrite files that already exist").flag()

    override fun execute() {
        val contract = dataset.resolve(mc ?: dataset.versions.last().id)
        val specs = uniforms.map(UniformSpec::parse)
        if (alwaysOn && contract.includeDirective != "include") {
            fail("minecraft:end_of_frame is a 26.3 feature; ${contract.id} does not read it.")
        }
        val scaffold = PostEffectScaffold(contract, namespace, id, specs, alwaysOn)
        val root = out ?: Path.of("pack")

        val files = scaffold.files()
        files.map { root.resolve(it.relative) }.firstOrNull { it.exists() && !force }?.let { existing ->
            fail("$existing already exists. Pass --force to overwrite it.")
        }
        files.forEach { file ->
            val target = root.resolve(file.relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, file.text)
            echo("wrote  ${file.relative}")
        }
        echo("")
        echo("Trigger it in game with: /posteffect add @s $namespace:$id")
        if (contract.includeDirective != "include") {
            echo("(${contract.id} has no /posteffect command; the effect is defined but only a mod or a later version can invoke it.)")
        }
        if (root.resolve("pack.mcmeta").exists().not()) {
            echo("No pack.mcmeta in $root yet; the pack needs one with min_format ${contract.packFormat} or lower.")
        }
    }
}
