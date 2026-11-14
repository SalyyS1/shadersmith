package com.salyvn.shadersmith.migrate

import com.github.ajalt.clikt.testing.test
import com.salyvn.shadersmith.cli.MigrateCommand
import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.ContractDataset
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Severity
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class MigratorTest {

    private val dataset = ContractDataset.load()
    private val pack = PackSource.read(Path.of("src/test/resources/oldpack"))

    @TempDir
    lateinit var directory: Path

    @Test
    fun `migrating to 26_3 rewrites what is mechanical and lists what is not`() {
        val result = Migrator(dataset.resolve("26.3-pre-1")).migrate(pack)
        val byFile = result.changes.associateBy { it.relative }

        val gui = byFile.getValue("assets/minecraft/shaders/core/gui.vsh").after!!
        assertTrue(gui.contains("mat4 ModelViewMat;\n    mat4 TextureMat;\n    vec4 ColorModulator;\n    vec3 ModelOffset;\n};"), gui)
        assertFalse(gui.contains("LineWidth"))
        assertTrue(gui.contains("gl_VertexIndex"))
        assertFalse(gui.contains("gl_VertexID"))

        val text = byFile.getValue("assets/minecraft/shaders/core/text.fsh").after!!
        assertTrue(text.contains("#include <minecraft:fog.glsl>"))
        assertFalse(text.contains("moj_import"))

        val fog = byFile.getValue("assets/minecraft/shaders/include/fog.glsl").after!!
        assertFalse(fog.contains("#version"))
        assertTrue(fog.startsWith("layout(std140) uniform Fog"))

        assertNull(byFile.getValue("assets/minecraft/shaders/core/rendertype_text.json").after, "deleted")

        // The location qualifiers need numbers chosen by a person, so they stay as errors; the note about
        // the overridden include and the mcmeta range warning are also left, because neither is a rewrite.
        val remainingErrors = result.remaining.filter { it.severity == Severity.ERROR }.map { it.rule }.toSet()
        assertEquals(setOf("SM050"), remainingErrors, result.remaining.joinToString { it.rule })
        assertTrue(result.remaining.any { it.rule == "SM072" }, "the mcmeta range is reported, not silently rewritten")
    }

    @Test
    fun `after migration, re-checking finds only what was left for a person`() {
        val target = dataset.resolve("26.3-pre-1")
        val result = Migrator(target).migrate(pack)

        // Rebuild a pack from the migrated text and check it again.
        val migrated = directory.resolve("migrated")
        pack.files.forEach { file ->
            val change = result.changes.firstOrNull { it.relative == file.relative }
            val text = if (change == null) file.text else change.after ?: return@forEach
            val path = migrated.resolve(file.relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
        }
        val again = ContractChecker(target).check(PackSource.read(migrated))
        val errors = again.filter { it.severity == Severity.ERROR }.map { it.rule }.toSet()

        assertEquals(setOf("SM050"), errors, again.joinToString("\n") { "${it.rule} ${it.file}:${it.line} ${it.message}" })
    }

    @Test
    fun `migrating to the version it already targets changes nothing but the stale block`() {
        val result = Migrator(dataset.resolve("1.21.10")).migrate(pack)
        val changed = result.changes.map { it.relative }

        // On 1.21.10 the block is right, so only the dead program JSON is touched.
        assertEquals(listOf("assets/minecraft/shaders/core/rendertype_text.json"), changed)
    }

    @Test
    fun `the diff for a deleted file points at dev null`() {
        val diff = UnifiedDiff.render("a/b.json", "{}\n", null)

        assertTrue(diff.contains("+++ /dev/null"))
        assertTrue(diff.contains("-{}"))
    }

    @Test
    fun `the diff for a one line change has context around it`() {
        val before = (1..10).joinToString("\n") { "line $it" }
        val after = before.replace("line 5", "changed")
        val diff = UnifiedDiff.render("x", before, after)

        assertTrue(diff.contains("-line 5"))
        assertTrue(diff.contains("+changed"))
        assertTrue(diff.contains(" line 4"))
        assertTrue(diff.contains(" line 6"))
        assertFalse(diff.contains(" line 1\n"), "far lines are not in the hunk")
    }

    @Test
    fun `a pack file knows its namespace and directory`() {
        val file = PackFile("assets/vnfx/shaders/post/vignette.fsh", "")

        assertEquals("vnfx", file.namespace)
        assertEquals("post", file.shaderDirectory)
        assertTrue(file.isShaderSource)
        assertFalse(file.isGlslInclude)
        assertTrue(PackFile("assets/vnfx/post_effect/vignette.json", "").isPostEffectDefinition)
    }

    @Test
    fun `migrating a zip pack preserves binary assets and writes deterministic zip with sha1`() {
        val stagedSrc = directory.resolve("src-pack")
        Files.walk(Path.of("src/test/resources/oldpack")).use { stream ->
            stream.forEach { src ->
                val rel = Path.of("src/test/resources/oldpack").relativize(src)
                val dst = stagedSrc.resolve(rel.toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst)
                }
            }
        }
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03)
        val pngPath = stagedSrc.resolve("assets/minecraft/textures/gui/hud_bar.png")
        Files.createDirectories(pngPath.parent)
        Files.write(pngPath, pngBytes)

        val inputZip = directory.resolve("input-pack.zip")
        PackSource.writeDeterministicZip(stagedSrc, inputZip)

        val outDir = directory.resolve("migrated-dir")
        val outZip1 = directory.resolve("migrated-1.zip")
        val outZip2 = directory.resolve("migrated-2.zip")

        val res1 = MigrateCommand().test(
            listOf(
                inputZip.toString(),
                "--to",
                "26.3-pre-1",
                "--write",
                "--out",
                outDir.toString(),
                "--zip",
                outZip1.toString(),
            ),
        )
        assertEquals(0, res1.statusCode, res1.stderr)
        assertTrue(res1.output.contains("resource-pack-sha1="), res1.output)

        assertTrue(Files.exists(outDir.resolve("pack.mcmeta")), "pack.mcmeta preserved from zip")
        assertArrayEquals(pngBytes, Files.readAllBytes(outDir.resolve("assets/minecraft/textures/gui/hud_bar.png")))
        assertFalse(Files.exists(outDir.resolve("assets/minecraft/shaders/core/rendertype_text.json")))
        assertTrue(
            Files.readString(outDir.resolve("assets/minecraft/shaders/core/gui.vsh")).contains("gl_VertexIndex"),
        )

        val res2 = MigrateCommand().test(
            listOf(
                inputZip.toString(),
                "--to",
                "26.3-pre-1",
                "--zip",
                outZip2.toString(),
            ),
        )
        assertEquals(0, res2.statusCode, res2.stderr)
        assertArrayEquals(Files.readAllBytes(outZip1), Files.readAllBytes(outZip2), "zip output must be byte-deterministic")

        ZipFile(outZip2.toFile()).use { zf ->
            val pngEntry = zf.getEntry("assets/minecraft/textures/gui/hud_bar.png")
            assertTrue(pngEntry != null, "binary texture preserved inside output zip")
            assertNull(zf.getEntry("assets/minecraft/shaders/core/rendertype_text.json"))
        }
    }

    @Test
    fun `extractAllSafely rejects zip slip traversal entries`() {
        val evilZip = directory.resolve("evil.zip")
        ZipOutputStream(Files.newOutputStream(evilZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../escaped.txt"))
            zos.write("boom".toByteArray())
            zos.closeEntry()
        }
        val target = directory.resolve("extract-dest")
        assertThrows(IOException::class.java) {
            PackSource.extractAllSafely(evilZip, target)
        }
    }
}
