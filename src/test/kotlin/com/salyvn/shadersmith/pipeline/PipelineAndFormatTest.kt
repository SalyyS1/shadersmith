package com.salyvn.shadersmith.pipeline

import com.github.ajalt.clikt.testing.test
import com.salyvn.shadersmith.cli.PipelineCommand
import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.ContractDataset
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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

class PipelineAndFormatTest {

    private val dataset = ContractDataset.load()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `links 26_3 core shader pairs with preprocessor branches OIT guards and SM052 SM053 SM054`() {
        val packDir = tempDir.resolve("pipeline-pack")
        Files.createDirectories(packDir.resolve("assets/minecraft/shaders/core"))
        Files.createDirectories(packDir.resolve("assets/myfx/shaders/core"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """{ "pack": { "min_format": 97, "max_format": 97 } }""",
        )

        // 1. Valid 26.3 Wavelet OIT entity pair using mutually exclusive #ifdef PER_FACE_LIGHTING / #else on location 2
        //    and #ifndef OIT_ALPHA_ONLY around fragColor!
        Files.writeString(
            packDir.resolve("assets/minecraft/shaders/core/entity.vsh"),
            """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            layout(location = 0) in vec3 Position;
            layout(location = 0) out float sphericalVertexDistance;
            #ifdef PER_FACE_LIGHTING
            layout(location = 2) out vec4 vertexPerFaceColorBack;
            layout(location = 3) out vec4 vertexPerFaceColorFront;
            #else
            layout(location = 2) out vec4 vertexColor;
            #endif
            layout(location = 6) flat out int materialId;
            void main() {
                gl_Position = vec4(Position, 1.0);
                sphericalVertexDistance = 0.0;
                materialId = 1;
            }
            """.trimIndent(),
        )
        Files.writeString(
            packDir.resolve("assets/minecraft/shaders/core/entity.fsh"),
            """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            #include <minecraft:oit.glsl>
            uniform sampler2D Sampler0;
            layout(location = 0) in float sphericalVertexDistance;
            #ifdef PER_FACE_LIGHTING
            layout(location = 2) in vec4 vertexPerFaceColorBack;
            layout(location = 3) in vec4 vertexPerFaceColorFront;
            #else
            layout(location = 2) in vec4 vertexColor;
            #endif
            layout(location = 6) flat in int materialId;
            #ifndef OIT_ALPHA_ONLY
            layout(location = 0) out vec4 fragColor;
            #endif
            void main() {
                #ifdef OIT_ALPHA_ONLY
                executeAlphaOnlyPhase(gl_FragCoord.z, 1.0);
                #else
                fragColor = texture(Sampler0, vec2(sphericalVertexDistance, float(materialId)));
                #endif
            }
            """.trimIndent(),
        )

        // 2. Broken custom core shader pair:
        //    - vsh has mat4 at location 0 (occupying 0..3) colliding with vec2 at location 3 (SM053)
        //    - fsh has non-flat integer input at location 4 (SM053), mismatched type at location 3 (vec3 vs vec2, SM052),
        //      missing vsh output at location 5 (SM052), and unguarded location 0 out with <minecraft:oit.glsl> (SM054)
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/core/broken.vsh"),
            """
            #version 330
            layout(location = 0) in vec3 Position;
            layout(location = 0) out mat4 basis;
            layout(location = 3) out vec2 uv;
            layout(location = 4) flat out int idOut;
            void main() { gl_Position = vec4(Position, 1.0); }
            """.trimIndent(),
        )
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/core/broken.fsh"),
            """
            #version 330
            #include <minecraft:oit.glsl>
            layout(location = 3) in vec3 uvWrongType;
            layout(location = 4) in int idMissingFlat;
            layout(location = 5) in vec4 unlinkedInput;
            layout(location = 0) out vec4 fragColor;
            void main() { fragColor = unlinkedInput; }
            """.trimIndent(),
        )

        val findings = ContractChecker(dataset.resolve("26.3")).check(PackSource.read(packDir))

        // Valid entity.vsh + entity.fsh must produce ZERO findings!
        val entityFindings = findings.filter { it.file.contains("minecraft/shaders/core/entity") }
        assertTrue(entityFindings.isEmpty(), entityFindings.joinToString("\n") { "${it.rule}: ${it.message}" })

        // Broken pair triggers SM052, SM053, and SM054
        assertTrue(findings.any { it.rule == "SM053" && it.file.endsWith("broken.vsh") && it.message.contains("location = 3") })
        assertTrue(findings.any { it.rule == "SM053" && it.file.endsWith("broken.fsh") && it.message.contains("flat") })
        assertTrue(findings.any { it.rule == "SM052" && it.file.endsWith("broken.fsh") && it.message.contains("location = 3") })
        assertTrue(findings.any { it.rule == "SM052" && it.file.endsWith("broken.fsh") && it.message.contains("location = 5") })
        assertTrue(findings.any { it.rule == "SM054" && it.severity == Severity.ERROR && it.file.endsWith("broken.fsh") })

        // Verify `shadersmith pipeline` CLI compiles permutations for minecraft:entity
        val pipeRes = PipelineCommand().test(
            listOf(packDir.toString(), "--mc", "26.3", "--program", "minecraft:entity", "--json"),
        )
        assertEquals(0, pipeRes.statusCode, pipeRes.stderr)
        val compiled = Json.parseToJsonElement(pipeRes.stdout).jsonArray.single().jsonObject
        assertTrue(compiled.getValue("usesWaveletOit").jsonPrimitive.boolean)
        val stateNames = compiled.getValue("permutations").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }
        assertTrue("OPAQUE" in stateNames)
        assertTrue("PER_FACE_LIGHTING" in stateNames)
        assertTrue("OIT_DEPTH_BOUNDS" in stateNames)
        assertTrue("OIT_TRANSMITTANCE" in stateNames)
        assertTrue("OIT_ACCUMULATE" in stateNames)
    }

    @Test
    fun `validates multi-pass post effect texture formats ping-pong feedback and persistent targets`() {
        val packDir = tempDir.resolve("multipass-pack")
        Files.createDirectories(packDir.resolve("assets/myfx/post_effect"))
        Files.createDirectories(packDir.resolve("assets/myfx/shaders/post"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """{ "pack": { "min_format": 97, "max_format": 97 } }""",
        )

        Files.writeString(
            packDir.resolve("assets/myfx/shaders/post/classify.fsh"),
            """
            #version 330
            uniform sampler2D InSampler;
            uniform usampler2D IdMaskSampler;
            uniform sampler2D DepthSampler;
            layout(location = 0) in vec2 texCoord;
            layout(location = 0) out vec4 fragColor;
            void main() {
                uvec4 mask = texture(IdMaskSampler, texCoord);
                fragColor = texture(InSampler, texCoord) + vec4(float(mask.r)) + texture(DepthSampler, texCoord);
            }
            """.trimIndent(),
        )

        // Broken post_effect:
        //  - bad_dim target defines width without height (SM076 error) and is never read (SM076 warning)
        //  - history target has "persistent": true -> reading before writing in Pass #1 must NOT trigger SM065!
        //  - id_buf has format "RGBA8" (float) but shader declares `usampler2D IdMaskSampler` -> SM069 error!
        //  - int_buf has format "R32UI" with "bilinear": true -> SM069 error!
        //  - Depth input has "use_depth_buffer": true + "bilinear": true -> SM069 warning!
        //  - Pass #2 reads "history" and writes "history" in the same pass -> SM069 feedback-loop error!
        //  - Texture location input "myfx:lut" omits width/height -> SM076 error!
        Files.writeString(
            packDir.resolve("assets/myfx/post_effect/temporal.json"),
            """
            {
              "targets": {
                "history": { "persistent": true, "format": "RGBA16F", "clear_color": [0.0, 0.0, 0.0, 1.0] },
                "id_buf": { "width": 512, "height": 512, "format": "RGBA8" },
                "int_buf": { "format": "R32UI" },
                "bad_dim": { "width": 256 }
              },
              "passes": [
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [
                    { "sampler_name": "In", "target": "minecraft:main" }
                  ],
                  "output": "id_buf"
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [
                    { "sampler_name": "In", "target": "int_buf", "bilinear": true }
                  ],
                  "output": "int_buf"
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "myfx:post/classify",
                  "inputs": [
                    { "sampler_name": "In", "target": "history" },
                    { "sampler_name": "IdMask", "target": "id_buf" },
                    { "sampler_name": "Depth", "target": "minecraft:main", "use_depth_buffer": true, "bilinear": true },
                    { "sampler_name": "Lut", "location": "myfx:textures/effect/lut" }
                  ],
                  "output": "history"
                }
              ]
            }
            """.trimIndent(),
        )

        val findings = ContractChecker(dataset.resolve("26.3")).check(PackSource.read(packDir))

        assertFalse(
            findings.any { it.rule == "SM065" && it.message.contains("'history'") },
            "persistent: true target may be read before write without SM065",
        )
        assertTrue(findings.any { it.rule == "SM069" && it.severity == Severity.ERROR && it.message.contains("usampler2D IdMaskSampler") })
        assertTrue(findings.any { it.rule == "SM069" && it.severity == Severity.ERROR && it.message.contains("bilinear: true' on integer") })
        assertTrue(findings.any { it.rule == "SM069" && it.severity == Severity.ERROR && it.message.contains("feedback loop") })
        assertTrue(findings.any { it.rule == "SM069" && it.severity == Severity.WARNING && it.message.contains("depth attachment") })
        assertTrue(findings.any { it.rule == "SM076" && it.severity == Severity.ERROR && it.message.contains("'width' without 'height'") })
        assertTrue(findings.any { it.rule == "SM076" && it.severity == Severity.ERROR && it.message.contains("myfx:textures/effect/lut") })
        assertTrue(findings.any { it.rule == "SM076" && it.severity == Severity.WARNING && it.message.contains("'bad_dim'") })
    }
}
