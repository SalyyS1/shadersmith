package com.salyvn.shadersmith.migrate

import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.ContractDataset
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
}
