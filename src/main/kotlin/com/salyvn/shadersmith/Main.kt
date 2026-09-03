package com.salyvn.shadersmith

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.salyvn.shadersmith.cli.CheckCommand
import com.salyvn.shadersmith.cli.ContractCommand
import com.salyvn.shadersmith.cli.MigrateCommand
import com.salyvn.shadersmith.cli.NewCommand
import com.salyvn.shadersmith.core.BuildInfo

/**
 * Entry point.
 *
 * The client does not validate a resource pack's shaders against anything; it compiles them, and if they
 * compile, it runs them. A shader written for the previous version compiles fine and reads the wrong
 * uniform. This tool holds the per-version contract those shaders have to meet and checks, rewrites, or
 * scaffolds against it.
 */
class ShadersmithCli : CliktCommand(name = "shadersmith") {

    override fun help(context: Context) =
        "Check, migrate and scaffold Minecraft resource pack shaders against each version's real contract."

    override fun run() = Unit
}

fun main(args: Array<String>) = ShadersmithCli()
    .versionOption(BuildInfo.version, names = setOf("--version"))
    .subcommands(
        CheckCommand(),
        MigrateCommand(),
        NewCommand(),
        ContractCommand(),
    )
    .main(args)
