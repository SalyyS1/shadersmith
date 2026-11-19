package com.salyvn.shadersmith

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.salyvn.shadersmith.cli.CheckCommand
import com.salyvn.shadersmith.cli.ContractCommand
import com.salyvn.shadersmith.cli.LayoutCommand
import com.salyvn.shadersmith.cli.MigrateCommand
import com.salyvn.shadersmith.cli.NewCommand
import com.salyvn.shadersmith.cli.PipelineCommand
import com.salyvn.shadersmith.cli.PressureCommand
import com.salyvn.shadersmith.cli.SpirvCommand
import com.salyvn.shadersmith.cli.VramCommand
import com.salyvn.shadersmith.core.BuildInfo

/**
 * Entry point.
 *
 * The client does not validate a resource pack's shaders against anything; it compiles them, and if they
 * compile, it runs them. A shader written for the previous version compiles fine and reads the wrong
 * uniform. This tool holds the per-version contract those shaders have to meet and checks, rewrites,
 * computes std140/std430 buffer layouts, compiles 26.3 pipeline states, or scaffolds against it.
 */
class ShadersmithCli : CliktCommand(name = "shadersmith") {

    override fun help(context: Context) =
        "Check, migrate, inspect buffer layouts, compile pipeline states, and scaffold Minecraft shaders."

    override fun run() = Unit
}

fun main(args: Array<String>) = ShadersmithCli()
    .versionOption(BuildInfo.version, names = setOf("--version"))
    .subcommands(
        CheckCommand(),
        MigrateCommand(),
        NewCommand(),
        ContractCommand(),
        LayoutCommand(),
        PipelineCommand(),
        SpirvCommand(),
        PressureCommand(),
        VramCommand(),
    )
    .main(args)
