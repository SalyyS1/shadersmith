package com.salyvn.shadersmith.layout

import com.github.ajalt.clikt.testing.test
import com.salyvn.shadersmith.cli.LayoutCommand
import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.ContractDataset
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.pack.PackingStandard
import com.salyvn.shadersmith.report.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BufferLayoutTest {

    private val dataset = ContractDataset.load()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `computes exact std140 vs std430 offsets strides structs and unsized tail arrays`() {
        val glsl = GlslSource(
            """
            #version 430
            struct Node {
                float weight;
                vec2 uv;
            };
            layout(std430, binding = 2) buffer SceneSsbo {
                vec3 origin;
                float radius;
                vec3 tint;
                vec2 offsetUv;
                float weights[4];
                mat2 rot;
                Node node;
                vec4 particles[];
            } scene;
            """.trimIndent(),
        )
        val structs = glsl.structs.associateBy { it.name }
        val block = glsl.bufferBlocks.single()

        val std430 = BufferLayoutCalculator.compute(block, structs, PackingStandard.STD430)
        val byName430 = std430.members.associateBy { it.name }

        // vec3 origin [0..12), float radius packs into [12..16) with 0 padding!
        assertEquals(0, byName430.getValue("origin").offset)
        assertEquals(12, byName430.getValue("origin").size)
        assertEquals(12, byName430.getValue("radius").offset)
        assertEquals(0, byName430.getValue("radius").paddingBefore)

        // vec3 tint [16..28), followed by vec2 offsetUv (align 8) -> 4B padding [28..32), offsetUv at [32..40)
        assertEquals(16, byName430.getValue("tint").offset)
        assertEquals(32, byName430.getValue("offsetUv").offset)
        assertEquals(4, byName430.getValue("offsetUv").paddingBefore)

        // float weights[4]: in std430 stride is 4B (16B total) at [40..56)
        assertEquals(40, byName430.getValue("weights").offset)
        assertEquals(4, byName430.getValue("weights").arrayStride)
        assertEquals(16, byName430.getValue("weights").size)

        // mat2 rot: in std430 2 columns of vec2 -> matrixStride 8B, size 16B at [56..72)
        assertEquals(56, byName430.getValue("rot").offset)
        assertEquals(8, byName430.getValue("rot").matrixStride)
        assertEquals(16, byName430.getValue("rot").size)

        // struct Node { float weight (0..4); pad 4; vec2 uv (8..16); } -> in std430 align=8, size=16 at [72..88)
        assertEquals(72, byName430.getValue("node").offset)
        assertEquals(8, byName430.getValue("node").baseAlignment)
        assertEquals(16, byName430.getValue("node").size)

        // unsized tail vec4 particles[] -> align 16 -> 8B padding [88..96), offset 96, stride 16
        assertEquals(96, byName430.getValue("particles").offset)
        assertTrue(std430.hasUnsizedTailArray)
        assertEquals(16, std430.unsizedElementStride)

        // Compare against std140 on the exact same members:
        val std140 = BufferLayoutCalculator.compute(block, structs, PackingStandard.STD140)
        val byName140 = std140.members.associateBy { it.name }
        assertEquals(16, byName140.getValue("weights").arrayStride, "std140 rounds float[] stride up to 16B")
        assertEquals(64, byName140.getValue("weights").size)
        assertEquals(16, byName140.getValue("rot").matrixStride, "std140 rounds mat2 column stride up to 16B")
        assertEquals(32, byName140.getValue("rot").size)
        assertEquals(16, byName140.getValue("node").baseAlignment, "std140 rounds struct alignment up to 16B")
        assertTrue(std140.fixedByteSize > std430.fixedByteSize)
    }

    @Test
    fun `detects SM042 SM043 SM044 and SM045 across broken SSBO and uniform blocks`() {
        val packDir = tempDir.resolve("ssbo-broken")
        Files.createDirectories(packDir.resolve("assets/myfx/shaders/core"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """{ "pack": { "min_format": 97, "max_format": 97 } }""",
        )
        // 1) #version 330 without GL_ARB_shader_storage_buffer_object, std430 on uniform, mid-block unsized array,
        //    duplicate binding = 0, mutating readonly buffer, reading writeonly buffer, and vec3[] stride trap.
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/core/compute_like.fsh"),
            """
            #version 330
            layout(std430) uniform IllegalStd430Uniform {
                float bad[];
            };
            layout(std430, binding = 0) readonly buffer ReadOnlyBuf {
                vec4 values[];
                float trailingAfterUnsized;
            } roBuf;
            layout(std430, binding = 0) writeonly buffer WriteOnlyBuf {
                vec3 positions[];
            } woBuf;
            layout(location = 0) out vec4 fragColor;
            void main() {
                roBuf.values[0] = vec4(1.0);
                int n = woBuf.positions.length();
                vec3 leaked = woBuf.positions[0];
                fragColor = vec4(leaked, float(n));
            }
            """.trimIndent(),
        )

        val findings = ContractChecker(dataset.resolve("26.3")).check(PackSource.read(packDir))
        val rules = findings.map { it.rule }.toSet()

        assertTrue("SM042" in rules, "std430 uniform and SSBO without extension on #version 330")
        assertEquals(2, findings.count { it.rule == "SM042" && it.severity == Severity.ERROR })
        assertTrue(findings.any { it.rule == "SM043" && it.message.contains("IllegalStd430Uniform") })
        assertTrue(findings.any { it.rule == "SM043" && it.message.contains("final member") })
        assertTrue(findings.any { it.rule == "SM043" && it.message.contains("binding = 0") })
        assertTrue(findings.any { it.rule == "SM044" && it.message.contains("readonly SSBO 'ReadOnlyBuf'") })
        assertTrue(findings.any { it.rule == "SM044" && it.message.contains("writeonly SSBO 'WriteOnlyBuf'") })
        assertTrue(findings.any { it.rule == "SM045" && it.severity == Severity.WARNING && it.message.contains("vec3") })
    }

    @Test
    fun `validates clean SSBO with GL_ARB_shader_storage_buffer_object and writeonly length call`() {
        val packDir = tempDir.resolve("ssbo-clean")
        Files.createDirectories(packDir.resolve("assets/myfx/shaders/core"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """{ "pack": { "min_format": 97, "max_format": 97 } }""",
        )
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/core/clean_ssbo.fsh"),
            """
            #version 330
            #extension GL_ARB_shader_storage_buffer_object : require
            layout(std430, binding = 0) readonly buffer InputBuf {
                vec4 items[];
            } inBuf;
            layout(std430, binding = 1) writeonly buffer OutputBuf {
                vec4 results[];
            } outBuf;
            layout(location = 0) out vec4 fragColor;
            void main() {
                int count = outBuf.results.length();
                if (count > 0) {
                    outBuf.results[0] = inBuf.items[0];
                }
                fragColor = inBuf.items[0];
            }
            """.trimIndent(),
        )

        val findings = ContractChecker(dataset.resolve("26.3")).check(PackSource.read(packDir))
        assertTrue(findings.isEmpty(), findings.joinToString("\n") { "${it.rule}: ${it.message}" })
    }

    @Test
    fun `layout CLI subcommand exports JSON and text tables for shader files`() {
        val shader = tempDir.resolve("sample.glsl")
        Files.writeString(
            shader,
            """
            #version 430
            layout(std430, binding = 1) buffer ParticleSsbo {
                vec3 pos;
                float scale;
                float weights[4];
            };
            """.trimIndent(),
        )

        val jsonRes = LayoutCommand().test(listOf(shader.toString(), "--json"))
        assertEquals(0, jsonRes.statusCode, jsonRes.stderr)
        val arr = Json.parseToJsonElement(jsonRes.stdout).jsonArray
        assertEquals(1, arr.size)
        val obj = arr.single().jsonObject
        val layout = obj.getValue("layout").jsonObject
        assertEquals("ParticleSsbo", layout.getValue("blockName").jsonPrimitive.content)
        assertEquals("std430", layout.getValue("packing").jsonPrimitive.content)
        assertEquals(32, layout.getValue("fixedByteSize").jsonPrimitive.int)
        assertEquals(80, obj.getValue("alternateByteSize").jsonPrimitive.int, "std140 expands float[4] to 64B -> 80B total")

        val textRes = LayoutCommand().test(listOf(shader.toString()))
        assertEquals(0, textRes.statusCode)
        assertTrue(textRes.stdout.contains("buffer ParticleSsbo (std430, binding = 1)"))
        assertFalse(textRes.stdout.contains("WARN"))
    }
}
