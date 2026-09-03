package com.salyvn.shadersmith.pack

import com.salyvn.shadersmith.contract.BlockMember
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GlslSourceTest {

    @Test
    fun `the version line is found with its line number`() {
        val source = GlslSource("// comment\n#version 330\nvoid main() {}")

        assertEquals(330 to 2, source.version)
        assertNull(GlslSource("void main() {}").version)
    }

    @Test
    fun `both include spellings are read with their target`() {
        val source = GlslSource("#moj_import <minecraft:fog.glsl>\n#include <minecraft:light.glsl>\n#include \"local.glsl\"")

        assertEquals(listOf("moj_import", "include", "include"), source.includes.map { it.directive })
        assertEquals(listOf("minecraft:fog.glsl", "minecraft:light.glsl", "local.glsl"), source.includes.map { it.target })
        assertEquals(listOf(1, 2, 3), source.includes.map { it.line })
    }

    @Test
    fun `a uniform block keeps its members in declaration order`() {
        val source = GlslSource(
            "#version 330\n\nlayout(std140) uniform DynamicTransforms {\n    mat4 ModelViewMat;\n    vec4 ColorModulator; // trailing\n    vec3 ModelOffset;\n};\n",
        )
        val block = source.blocks.single()

        assertEquals("DynamicTransforms", block.name)
        assertEquals(3, block.line)
        assertEquals(
            listOf(BlockMember("mat4", "ModelViewMat"), BlockMember("vec4", "ColorModulator"), BlockMember("vec3", "ModelOffset")),
            block.members,
        )
    }

    @Test
    fun `bare in and out declarations are found but qualified ones are not`() {
        val source = GlslSource("in vec3 Position;\nlayout(location = 1) in vec4 Color;\nout vec4 vertexColor; // x\nuniform sampler2D Sampler0;")

        assertEquals(listOf("in vec3 Position;" to 1, "out vec4 vertexColor; // x" to 3), source.unqualifiedInterfaceVariables)
    }

    @Test
    fun `samplers and gl_VertexID are located`() {
        val source = GlslSource("uniform sampler2D InSampler;\nuniform samplerCube Sky;\nvoid main() { int i = gl_VertexID; }")

        assertEquals(listOf("InSampler", "Sky"), source.samplers)
        assertEquals(listOf(3), source.vertexIdLines)
    }
}
