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
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Rewrites what can be rewritten mechanically for a target version, and shows the diff before touching
 * anything.
 *
 * Default is a diff on stdout. `--write` applies it in place for a directory, into `--out`, and/or into a
 * byte-reproducible `--zip` archive (copying all non-shader pack files from directory or `.zip` inputs so
 * the output pack remains complete).
 */
class MigrateCommand : ShadersmithCommand(name = "migrate") {

    override fun help(context: Context) = "Rewrite a pack's shaders for a newer version, showing the diff first."

    private val pack by argument("pack", help = "Pack directory or .zip archive").path(mustExist = true)

    private val to by option("--to", help = "Target version, e.g. 26.3-pre-1").required()

    private val write by option("--write", help = "Apply the changes instead of printing the diff").flag()

    private val out by option("--out", help = "Write the migrated pack directory here instead of in place").path()

    private val zip by option(
        "--zip",
        help = "Write a byte-deterministic migrated .zip archive and print its resource-pack-sha1 digest",
    ).path()

    private val force by option("--force", help = "Overwrite an existing --zip archive").flag()

    override fun execute() {
        val target = dataset.resolve(to)
        val source = PackSource.read(pack)
        val result = Migrator(target).migrate(source)

        val applying = write || zip != null
        val zipTarget = zip
        if (applying && zipTarget != null && zipTarget.exists() && !force) {
            fail("$zipTarget already exists. Pass --force to overwrite it.")
        }

        if (result.changes.isEmpty() && zipTarget == null) {
            echo("Nothing to rewrite for ${target.id}.")
        } else if (!applying) {
            result.changes.forEach { change -> echo(UnifiedDiff.render(change.relative, change.before, change.after).trimEnd()) }
            echo("")
            echo("${result.changes.size} file(s) would change. Pass --write (or --zip <out.zip>) to apply.")
        } else {
            val tempStaged = if (out == null && zipTarget != null) {
                Files.createTempDirectory("shadersmith-migrate-")
            } else {
                null
            }
            try {
                val destination = out
                    ?: tempStaged
                    ?: if (pack.isDirectory()) pack else fail("A zip cannot be rewritten in place; give --out <dir> or --zip <file>.")

                if (destination != pack) {
                    if (pack.isDirectory()) {
                        copyTree(pack, destination)
                    } else if (pack.isRegularFile()) {
                        PackSource.extractAllSafely(pack, destination)
                    }
                }

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
                if (tempStaged == null) {
                    echo("${result.changes.size} file(s) written to $destination")
                }
                if (zipTarget != null) {
                    val sha1 = PackSource.writeDeterministicZip(destination, zipTarget)
                    echo("Zipped migrated pack to $zipTarget")
                    echo("resource-pack-sha1=$sha1")
                }
            } finally {
                tempStaged?.toFile()?.deleteRecursively()
            }
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
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
