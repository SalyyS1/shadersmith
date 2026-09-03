package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.shadersmith.migrate.Migrator
import com.salyvn.shadersmith.migrate.UnifiedDiff
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.ConsoleReporter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Rewrites what can be rewritten mechanically for a target version, and shows the diff before touching
 * anything.
 *
 * Default is a diff on stdout. `--write` applies it in place for a directory, or into `--out` for a zip or
 * when you would rather keep the original. What cannot be fixed by a rule — a `layout(location)` that
 * needs a number chosen, a post effect whose shape changed twice — is listed afterwards, because pretending
 * to have migrated those is how a pack ships broken.
 */
class MigrateCommand : ShadersmithCommand(name = "migrate") {

    override fun help(context: Context) = "Rewrite a pack's shaders for a newer version, showing the diff first."

    private val pack by argument("pack", help = "Pack directory or zip").path(mustExist = true)

    private val to by option("--to", help = "Target version, e.g. 26.3-pre-1").required()

    private val write by option("--write", help = "Apply the changes instead of printing the diff").flag()

    private val out by option("--out", help = "Write the migrated pack here instead of in place").path()

    override fun execute() {
        val target = dataset.resolve(to)
        val source = PackSource.read(pack)
        val result = Migrator(target).migrate(source)

        if (result.changes.isEmpty()) {
            echo("Nothing to rewrite for ${target.id}.")
        } else if (!write) {
            result.changes.forEach { change -> echo(UnifiedDiff.render(change.relative, change.before, change.after).trimEnd()) }
            echo("")
            echo("${result.changes.size} file(s) would change. Pass --write to apply.")
        } else {
            val destination = out ?: if (pack.isDirectory()) pack else fail("A zip cannot be rewritten in place; give --out.")
            if (out != null && pack.isDirectory()) copyTree(pack, destination)
            result.changes.forEach { change ->
                val file = destination.resolve(change.relative)
                if (change.after == null) {
                    Files.deleteIfExists(file)
                    echo("deleted   ${change.relative}")
                } else {
                    Files.createDirectories(file.parent)
                    Files.writeString(file, change.after)
                    echo("rewrote   ${change.relative}")
                }
            }
            echo("${result.changes.size} file(s) written to $destination")
        }

        if (result.remaining.isNotEmpty()) {
            echo("")
            echo("Still needs a person, for ${target.id}:")
            ConsoleReporter.lines(result.remaining, pack.fileName.toString()).drop(1).forEach { echo(it) }
        }
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { file ->
                val target = to.resolve(from.relativize(file).toString())
                if (Files.isDirectory(file)) Files.createDirectories(target) else {
                    Files.createDirectories(target.parent)
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
