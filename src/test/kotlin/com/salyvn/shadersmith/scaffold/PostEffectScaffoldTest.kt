package com.salyvn.shadersmith.scaffold

import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.ContractDataset
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PostEffectScaffoldTest {

    private val dataset = ContractDataset.load()

    @TempDir
    lateinit var directory: Path

    @Test
    fun `a uniform spec parses name type and value`() {
        val spec = UniformSpec.parse("Tint:vec3=0.1,0,0.5")

        assertEquals("Tint", spec.name)
        assertEquals("vec3", spec.type)
        assertEquals(listOf(0.1, 0.0, 0.5), spec.value)
        assertEquals("[ 0.1, 0.0, 0.5 ]", spec.jsonValue())
        assertEquals("0.6", UniformSpec.parse("Strength:float=0.6").jsonValue())
        assertEquals(listOf(0.0, 0.0), UniformSpec.parse("Dir:vec2").value, "a missing value is zeros")
    }

    @Test
    fun `a wrong arity or unknown type is refused by name`() {
        assertTrue(assertThrows<IllegalArgumentException> { UniformSpec.parse("A:vec2=1") }.message!!.contains("2 value"))
        assertTrue(assertThrows<IllegalArgumentException> { UniformSpec.parse("A:mat4=1") }.message!!.contains("mat4"))
    }

    @Test
    fun `the scaffold declares the same uniforms in the json and the glsl`() {
        val scaffold = PostEffectScaffold(
            dataset.resolve("26.3-pre-1"),
            "vnfx",
            "vignette",
            listOf(UniformSpec.parse("Strength:float=0.6"), UniformSpec.parse("Tint:vec3=0.1,0,0")),
            alwaysOn = false,
        )
        val files = scaffold.files().associateBy { it.relative }

        val json = files.getValue("assets/vnfx/post_effect/vignette.json").text
        assertTrue(json.contains("\"VignetteConfig\""))
        assertTrue(json.contains("\"name\": \"Strength\", \"type\": \"float\", \"value\": 0.6"))
        assertTrue(json.contains("\"vertex_shader\": \"minecraft:core/screenquad\""))
        assertTrue(json.contains("\"fragment_shader\": \"vnfx:post/vignette\""))

        val glsl = GlslSource(files.getValue("assets/vnfx/shaders/post/vignette.fsh").text)
        val block = glsl.blocks.single { it.name == "VignetteConfig" }
        assertEquals(listOf("Strength", "Tint"), block.members.map { it.name })
        assertEquals(listOf("float", "vec3"), block.members.map { it.type })
        assertEquals(330 to 1, glsl.version)
        assertTrue(glsl.unqualifiedInterfaceVariables.isEmpty(), "26.3 needs layout(location) on every in/out")
        assertEquals(listOf("InSampler"), glsl.samplers)
    }

    @Test
    fun `the scaffold uses the older dialect for 1_21_6`() {
        val files = PostEffectScaffold(dataset.resolve("1.21.6"), "vnfx", "fade", emptyList(), false).files()
        val glsl = files.single { it.relative.endsWith(".fsh") }.text
        val json = files.single { it.relative.endsWith(".json") }.text

        assertTrue(glsl.startsWith("#version 150"))
        assertTrue(glsl.contains("in vec2 texCoord;"))
        assertFalse(glsl.contains("layout(location"))
        assertTrue(json.contains("\"vertex_shader\": \"minecraft:post/screenquad\""))
    }

    @Test
    fun `a version before the block form is refused`() {
        assertThrows<IllegalArgumentException> {
            PostEffectScaffold(dataset.resolve("1.21.5"), "vnfx", "x", emptyList(), false)
        }
    }

    @Test
    fun `what the scaffold writes passes its own checker on the target version`() {
        val contract = dataset.resolve("26.3-pre-1")
        val files = PostEffectScaffold(contract, "vnfx", "vignette", listOf(UniformSpec.parse("Strength:float=0.6")), alwaysOn = true).files()
        files.forEach { file ->
            val path = directory.resolve(file.relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, file.text)
        }

        val findings = ContractChecker(contract).check(PackSource.read(directory))

        assertTrue(findings.isEmpty(), findings.joinToString("\n") { "${it.rule} ${it.file}: ${it.message}" })
        assertTrue(Files.exists(directory.resolve("assets/minecraft/post_effect/end_of_frame.json")))
    }
}
