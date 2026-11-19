package com.salyvn.shadersmith.pressure

import com.github.ajalt.clikt.testing.test
import com.salyvn.shadersmith.cli.CheckCommand
import com.salyvn.shadersmith.cli.PressureCommand
import com.salyvn.shadersmith.cli.VramCommand
import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.ContractDataset
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Severity
import com.salyvn.shadersmith.vram.VramCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PressureAndVramTest {

    private val dataset = ContractDataset.load()
    private val v26_3 = dataset.resolve("26.3")

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `detects dead samplers naked uniforms and trailing custom std140 members while sparing vanilla blocks`() {
        val glsl = """
            #version 330
            uniform sampler2D ActiveSampler;
            uniform sampler2D UnusedSampler;
            uniform float DeadScalar;

            layout(std140) uniform SamplerInfo {
                vec2 OutSize;
                vec2 InSize;
            };

            layout(std140) uniform BloomParams {
                float LeadUnusedButPositional;
                float Threshold;
                vec4 TrailingDeadTint;
            };

            layout(std140) uniform GhostBlock {
                vec4 NeverRead;
            };

            layout(location = 0) in vec2 texCoord;
            layout(location = 0) out vec4 fragColor;

            void main() {
                fragColor = texture(ActiveSampler, texCoord) * Threshold;
            }
        """.trimIndent()

        val file = PackFile("assets/myfx/shaders/post/bloom.fsh", glsl)
        val findings = mutableListOf<Finding>()
        val report = RegisterPressureAnalyzer.checkFile(
            file = file,
            source = GlslSource(glsl),
            versionId = v26_3.id,
            vanillaUniformBlocks = v26_3.uniformBlocks.keys,
            out = findings,
        )

        val sm059 = findings.filter { it.rule == "SM059" }
        assertTrue(sm059.any { it.message.contains("UnusedSampler") })
        assertTrue(sm059.any { it.message.contains("DeadScalar") })
        assertTrue(sm059.any { it.message.contains("BloomParams.TrailingDeadTint") })
        assertTrue(sm059.any { it.message.contains("GhostBlock") })

        // LeadUnusedButPositional precedes used Threshold in std140, so removing it would shift byte offsets
        assertFalse(sm059.any { it.message.contains("LeadUnusedButPositional") })
        // SamplerInfo is an engine-bound vanilla uniform block and must not be flagged
        assertFalse(sm059.any { it.message.contains("SamplerInfo") })
        assertTrue("UnusedSampler" in report.deadSymbols)
    }

    @Test
    fun `extends live ranges across loop back-edges and flags register pressure above budget`() {
        val glsl = """
            #version 330
            uniform sampler2D InSampler;
            layout(location = 0) in vec2 texCoord;
            layout(location = 0) out vec4 fragColor;

            void main() {
                mat4 basisA = mat4(1.0);
                mat4 basisB = mat4(2.0);
                vec4 acc = vec4(0.0);
                for (int i = 0; i < 4; i = i + 1) {
                    vec4 tap = textureLod(InSampler, texCoord + vec2(float(i) * 0.01), 0.0);
                    acc += basisA * (basisB * tap);
                }
                vec4 deadTemp = vec4(3.0);
                fragColor = acc;
            }
        """.trimIndent()

        val file = PackFile("assets/myfx/shaders/post/heavy.fsh", glsl)
        val findings = mutableListOf<Finding>()
        val report = RegisterPressureAnalyzer.checkFile(
            file = file,
            source = GlslSource(glsl),
            versionId = v26_3.id,
            maxVec4Temps = 8,
            out = findings,
        )

        // basisA (4) + basisB (4) + acc (1) + i (1) + tap (1) = 11 live vec4 registers inside loop
        assertTrue(report.peakVec4Registers >= 11, "Expected >= 11 vec4 regs, got ${report.peakVec4Registers}")
        assertTrue(findings.any { it.rule == "SM077" && it.severity == Severity.WARNING })
        assertTrue(findings.any { it.rule == "SM079" && it.severity == Severity.NOTE && it.message.contains("deadTemp") })
    }

    @Test
    fun `flags dynamic indexing of local register arrays as scratch spill risk under SM077`() {
        val glsl = """
            #version 330
            layout(location = 0) flat in int tapIndex;
            layout(location = 0) out vec4 fragColor;

            void main() {
                vec4 kernel[8];
                kernel[0] = vec4(1.0);
                fragColor = kernel[tapIndex];
            }
        """.trimIndent()

        val file = PackFile("assets/myfx/shaders/core/spill.fsh", glsl)
        val findings = mutableListOf<Finding>()
        RegisterPressureAnalyzer.checkFile(
            file = file,
            source = GlslSource(glsl),
            versionId = v26_3.id,
            out = findings,
        )

        val spill = findings.firstOrNull { it.rule == "SM077" && it.message.contains("kernel") }
        assertNotNull(spill)
        assertTrue(spill!!.message.contains("scratch memory"))
    }

    @Test
    fun `distinguishes non-uniform divergent fragment derivatives from uniform branches and textureLod`() {
        val glsl = """
            #version 330
            uniform sampler2D InSampler;
            layout(std140) uniform Config {
                float Mode;
            };
            layout(location = 0) in vec2 texCoord;
            layout(location = 0) out vec4 fragColor;

            void main() {
                vec4 col = vec4(0.0);
                // Uniform branch: all 2x2 quad lanes take the same path -> safe
                if (Mode > 0.5) {
                    col = texture(InSampler, texCoord);
                }
                // Non-uniform branch with explicit LOD -> safe
                if (texCoord.x > 0.5) {
                    col += textureLod(InSampler, texCoord, 0.0);
                }
                // Non-uniform branch with implicit derivative texture() -> triggers SM078
                if (texCoord.y > 0.5) {
                    col += texture(InSampler, texCoord);
                }
                fragColor = col;
            }
        """.trimIndent()

        val file = PackFile("assets/myfx/shaders/post/branch.fsh", glsl)
        val findings = mutableListOf<Finding>()
        RegisterPressureAnalyzer.checkFile(
            file = file,
            source = GlslSource(glsl),
            versionId = v26_3.id,
            out = findings,
        )

        val sm078 = findings.filter { it.rule == "SM078" }
        assertEquals(1, sm078.size, sm078.joinToString("\n") { "${it.line}: ${it.message}" })
        assertTrue(sm078.single().message.contains("textureLod()"))
    }

    @Test
    fun `calculates post effect VRAM aliasing pools and detects SM080 SM081 SM082`() {
        val postJson = """
            {
              "targets": {
                "ping_a": { "format": "RGBA16F" },
                "pong_b": { "format": "RGBA16F" },
                "stage_c": { "format": "RGBA16F" },
                "history": { "persistent": true, "format": "RGBA32F" }
              },
              "passes": [
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [
                    { "sampler_name": "In", "target": "history" }
                  ],
                  "output": "ping_a"
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [
                    { "sampler_name": "In", "target": "minecraft:main" }
                  ],
                  "output": "ping_a"
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [
                    { "sampler_name": "In", "target": "ping_a" }
                  ],
                  "output": "pong_b"
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [
                    { "sampler_name": "In", "target": "pong_b" }
                  ],
                  "output": "stage_c"
                }
              ]
            }
        """.trimIndent()

        val file = PackFile("assets/myfx/post_effect/heavy_chain.json", postJson)
        val findings = mutableListOf<Finding>()
        val report = VramCalculator.analyzePostEffect(
            file = file,
            versionId = v26_3.id,
            viewportWidth = 1920,
            viewportHeight = 1080,
            fps = 60,
            budgetMb = 64.0,
            out = findings,
        )
        assertNotNull(report)
        val rep = report!!

        // 3 * RGBA16F (8B * 1920 * 1080 = 16,588,800 B) + 1 * RGBA32F (16B * 1920 * 1080 = 33,177,600 B) = 82,944,000 B (~79.1 MiB)
        assertEquals(82_944_000L, rep.allocatedVramBytes)
        // stage_c [W:4] does not overlap ping_a [W:1..R:3], so stage_c aliases into ping_a's RGBA16F pool slot!
        assertEquals(16_588_800L, rep.reclaimableAliasBytes)
        assertEquals(66_355_200L, rep.aliasedPeakVramBytes)

        // SM080: ping_a written in Pass #1 and overwritten in Pass #2 before read
        assertTrue(findings.any { it.rule == "SM080" && it.message.contains("ping_a") })
        // SM081: history persistent without clear_color read before write + chain ends in transient stage_c without writing minecraft:main
        assertEquals(2, findings.count { it.rule == "SM081" })
        // SM082: 79.10 MiB > 64.00 MiB budget
        assertTrue(findings.any { it.rule == "SM082" })
    }

    @Test
    fun `CLI pressure vram and check --pressure commands emit JSON and SARIF v2_1_0`() {
        val packDir = tempDir.resolve("v06-cli-pack")
        Files.createDirectories(packDir.resolve("assets/myfx/shaders/include"))
        Files.createDirectories(packDir.resolve("assets/myfx/shaders/post"))
        Files.createDirectories(packDir.resolve("assets/myfx/post_effect"))

        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """{ "pack": { "min_format": 97, "max_format": 97 } }""",
        )
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/include/helper.glsl"),
            "vec4 applyHelper(sampler2D s, vec2 uv) { return textureLod(s, uv, 0.0); }\n",
        )
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/post/pass1.fsh"),
            """
            #version 330
            #include <myfx:helper.glsl>
            uniform sampler2D InSampler;
            uniform sampler2D DeadSampler;
            layout(location = 0) in vec2 texCoord;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = applyHelper(InSampler, texCoord);
            }
            """.trimIndent(),
        )
        Files.writeString(
            packDir.resolve("assets/myfx/post_effect/bloom.json"),
            """
            {
              "targets": {
                "ping": { "format": "RGBA16F" },
                "pong": { "format": "RGBA16F" }
              },
              "passes": [
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "myfx:post/pass1",
                  "inputs": [ { "sampler_name": "In", "target": "minecraft:main" } ],
                  "output": "ping"
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [ { "sampler_name": "In", "target": "ping" } ],
                  "output": "pong"
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [ { "sampler_name": "In", "target": "pong" } ],
                  "output": "minecraft:main"
                }
              ]
            }
            """.trimIndent(),
        )

        val sarifOut = tempDir.resolve("pressure.sarif")
        val presRes = PressureCommand().test(
            listOf(packDir.toString(), "--mc", "26.3", "--json", "--sarif", sarifOut.toString(), "--strict"),
        )
        // DeadSampler triggers SM059 warning, --strict turns warning into exit 1
        assertEquals(1, presRes.statusCode)
        assertTrue(presRes.stdout.contains("DeadSampler"))
        // InSampler is used via transitive include helper.glsl and must NOT be reported dead
        val presArr = Json.parseToJsonElement(presRes.stdout).jsonArray
        val pass1Obj = presArr.map { it.jsonObject }.single { it.getValue("file").jsonPrimitive.content.endsWith("pass1.fsh") }
        val deadSyms = pass1Obj.getValue("deadSymbols").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("DeadSampler"), deadSyms)
        assertTrue(Files.readString(sarifOut).contains("\"SM059\""))

        val vramRes = VramCommand().test(listOf(packDir.toString(), "--mc", "26.3", "--json"))
        assertEquals(0, vramRes.statusCode, vramRes.stderr)
        val vramObj = Json.parseToJsonElement(vramRes.stdout).jsonArray.single().jsonObject
        assertEquals(33_177_600L, vramObj.getValue("allocatedVramBytes").jsonPrimitive.long)

        val checkPres = CheckCommand().test(listOf(packDir.toString(), "--mc", "26.3", "--pressure", "--json"))
        assertTrue(checkPres.stdout.contains("\"SM059\""))

        // ContractChecker also exposes SM080 automatically when post-effect overwrites target before read
        val badPack = tempDir.resolve("bad-pingpong")
        Files.createDirectories(badPack.resolve("assets/myfx/post_effect"))
        Files.writeString(badPack.resolve("pack.mcmeta"), """{ "pack": { "min_format": 97, "max_format": 97 } }""")
        Files.writeString(
            badPack.resolve("assets/myfx/post_effect/clobber.json"),
            """
            {
              "targets": { "ping": {} },
              "passes": [
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [ { "sampler_name": "In", "target": "minecraft:main" } ],
                  "output": "ping"
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [ { "sampler_name": "In", "target": "minecraft:main" } ],
                  "output": "ping"
                }
              ]
            }
            """.trimIndent(),
        )
        val clobberFindings = ContractChecker(v26_3).check(PackSource.read(badPack))
        assertTrue(clobberFindings.any { it.rule == "SM080" })
        assertTrue(clobberFindings.any { it.rule == "SM081" })
    }
}
