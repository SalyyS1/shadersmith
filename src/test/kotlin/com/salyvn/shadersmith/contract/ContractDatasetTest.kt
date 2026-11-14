package com.salyvn.shadersmith.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dataset is generated from vanilla files, so these tests pin the facts the checker's rules depend on.
 * If one of them fails after regenerating, the game changed and a rule needs a look — that is the point.
 */
class ContractDatasetTest {

    private val dataset = ContractDataset.load()

    @Test
    fun `the dataset loads and is in release order`() {
        assertTrue(dataset.versions.size >= 10)
        val formats = dataset.versions.map { it.packFormat }
        assertEquals(formats.sorted(), formats)
    }

    @Test
    fun `program definitions under core vanish at 1_21_5`() {
        assertTrue(dataset.resolve("1.21.4").coreProgramJson)
        assertFalse(dataset.resolve("1.21.5").coreProgramJson)
        assertFalse(dataset.resolve("26.2").coreProgramJson)
    }

    @Test
    fun `LineWidth leaves DynamicTransforms at 1_21_11, not 1_21_9`() {
        fun members(id: String) = dataset.resolve(id).uniformBlocks.getValue("DynamicTransforms").map { it.name }

        assertTrue("LineWidth" in members("1.21.9"))
        assertTrue("LineWidth" in members("1.21.10"))
        assertFalse("LineWidth" in members("1.21.11"))
    }

    @Test
    fun `DynamicTransforms is reordered at 26_3`() {
        val before = dataset.resolve("26.2").uniformBlocks.getValue("DynamicTransforms").map { it.name }
        val after = dataset.resolve("26.3-pre-1").uniformBlocks.getValue("DynamicTransforms").map { it.name }

        assertEquals(listOf("ModelViewMat", "ColorModulator", "ModelOffset", "TextureMat"), before)
        assertEquals(listOf("ModelViewMat", "TextureMat", "ColorModulator", "ModelOffset"), after)
        assertEquals(before.toSet(), after.toSet(), "same members, which is exactly why it is silent")
    }

    @Test
    fun `the include directive and the version line change at 26_3`() {
        val old = dataset.resolve("26.2")
        val new = dataset.resolve("26.3-pre-1")

        assertEquals("moj_import", old.includeDirective)
        assertEquals("include", new.includeDirective)
        assertTrue(old.includesCarryVersion)
        assertFalse(new.includesCarryVersion)
    }

    @Test
    fun `glsl 150 becomes 330 at 1_21_9`() {
        assertEquals(150, dataset.resolve("1.21.8").glslVersion)
        assertEquals(330, dataset.resolve("1.21.9").glslVersion)
    }

    @Test
    fun `post passes move from post to core screenquad at 1_21_9`() {
        assertEquals("minecraft:post/screenquad", dataset.resolve("1.21.8").postPassVertexShader)
        assertEquals("minecraft:core/screenquad", dataset.resolve("1.21.9").postPassVertexShader)
    }

    @Test
    fun `transparency is gone at 26_3`() {
        assertTrue("transparency" in dataset.resolve("26.2").postEffects)
        assertFalse("transparency" in dataset.resolve("26.3-pre-1").postEffects)
    }

    @Test
    fun `a pack format number resolves to the newest version at or below it`() {
        assertEquals("26.2", dataset.resolve("88").id)
        assertEquals("26.2", dataset.resolve("90").id, "a snapshot between releases resolves to the release before it")
        assertEquals("1.21.9", dataset.resolve("69").id)
        assertEquals("26.3", dataset.resolve("97").id)
        assertEquals("26.3", dataset.resolve("97.1").id)
        assertEquals("26.3", dataset.resolve("26.3").id)
        assertEquals("26.3-pre-1", dataset.resolve("26.3-pre-1").id)
    }

    @Test
    fun `a release range skips intermediate prereleases unless explicitly requested`() {
        val releaseRange = dataset.range(dataset.resolve("26.1"), dataset.resolve("26.3"))
        assertEquals(listOf("26.1", "26.2", "26.3"), releaseRange.map { it.id })

        val preRange = dataset.range(dataset.resolve("26.2"), dataset.resolve("26.3-pre-1"))
        assertEquals(listOf("26.2", "26.3-pre-1"), preRange.map { it.id })
    }

    @Test
    fun `a range walks releases in order`() {
        val range = dataset.range(dataset.resolve("1.21.9"), dataset.resolve("26.1"))

        assertEquals(listOf("1.21.9", "1.21.10", "1.21.11", "26.1"), range.map { it.id })
    }

    @Test
    fun `every version knows its core files and includes`() {
        dataset.versions.forEach { version ->
            assertTrue(version.coreFiles.isNotEmpty(), version.id)
            assertNotNull(version.glslVersion, version.id)
        }
        assertTrue("dynamictransforms.glsl" in dataset.resolve("26.3-pre-1").includeFiles)
    }
}
