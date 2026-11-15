package com.salyvn.shadersmith.spirv

import com.github.ajalt.clikt.testing.test
import com.salyvn.shadersmith.cli.CheckCommand
import com.salyvn.shadersmith.cli.SpirvCommand
import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.ContractDataset
import com.salyvn.shadersmith.contract.VersionContract
import com.salyvn.shadersmith.layout.SsboValidator
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.pipeline.PipelineCompiler
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ComputeAndSpirvTest {

    private val dataset = ContractDataset.load()
    private val v26_3 = dataset.resolve("26.3")

    @Test
    fun `compute shader missing local_size workgroup in csh triggers SM046`() {
        val glsl = """
            #version 430
            layout(std430, binding = 0) buffer DataBuffer {
                uint values[];
            };
            void main() {
                values[0] = 42u;
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/compute/test.csh", "test.csh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SsboValidator.checkFile(file, src, v26_3.id, 430, emptyMap(), emptySet(), findings)
        assertTrue(findings.any { it.rule == "SM046" && it.severity == Severity.ERROR })
    }

    @Test
    fun `compute shader with valid workgroup passes SM046`() {
        val glsl = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(std430, binding = 0) buffer DataBuffer {
                uint values[];
            };
            void main() {
                barrier();
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/compute/test.csh", "test.csh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SsboValidator.checkFile(file, src, v26_3.id, 430, emptyMap(), emptySet(), findings)
        assertFalse(findings.any { it.rule == "SM046" })
        assertNotNull(src.computeWorkgroup)
        assertEquals(16, src.computeWorkgroup?.x)
        assertEquals(16, src.computeWorkgroup?.y)
        assertEquals(1, src.computeWorkgroup?.z)
    }

    @Test
    fun `compute shader exceeding workgroup invocations limit triggers SM046`() {
        val glsl = """
            #version 430
            layout(local_size_x = 32, local_size_y = 32, local_size_z = 2) in;
            void main() {}
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/compute/oversized.csh", "oversized.csh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SsboValidator.checkFile(file, src, v26_3.id, 430, emptyMap(), emptySet(), findings)
        val sm046 = findings.firstOrNull { it.rule == "SM046" }
        assertNotNull(sm046)
        assertTrue(sm046!!.message.contains("2048"))
    }

    @Test
    fun `divergent barrier inside conditional branch triggers SM047`() {
        val glsl = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) buffer DataBuffer {
                uint values[];
            };
            void main() {
                uint idx = gl_LocalInvocationIndex;
                if (idx < 10u) {
                    barrier();
                }
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/compute/divergent.csh", "divergent.csh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SsboValidator.checkFile(file, src, v26_3.id, 430, emptyMap(), emptySet(), findings)
        val sm047 = findings.firstOrNull { it.rule == "SM047" }
        assertNotNull(sm047)
        assertTrue(sm047!!.message.contains("divergent barrier hazard"))
    }

    @Test
    fun `convergent top-level barrier does not trigger SM047`() {
        val glsl = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) buffer DataBuffer {
                uint values[];
            };
            void main() {
                values[gl_LocalInvocationIndex] = 1u;
                barrier();
                uint readVal = values[gl_LocalInvocationIndex];
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/compute/convergent.csh", "convergent.csh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SsboValidator.checkFile(file, src, v26_3.id, 430, emptyMap(), emptySet(), findings)
        assertFalse(findings.any { it.rule == "SM047" })
    }

    @Test
    fun `non-coherent atomic operation on SSBO triggers SM048`() {
        val glsl = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) buffer CounterBuffer {
                uint counter;
            };
            void main() {
                atomicAdd(counter, 1u);
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/compute/atomic.csh", "atomic.csh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SsboValidator.checkFile(file, src, v26_3.id, 430, emptyMap(), emptySet(), findings)
        val sm048 = findings.firstOrNull { it.rule == "SM048" }
        assertNotNull(sm048)
        assertTrue(sm048!!.message.contains("coherent"))
    }

    @Test
    fun `coherent atomic operation passes SM048`() {
        val glsl = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) coherent buffer CounterBuffer {
                uint counter;
            };
            void main() {
                atomicAdd(counter, 1u);
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/compute/atomic_ok.csh", "atomic_ok.csh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SsboValidator.checkFile(file, src, v26_3.id, 430, emptyMap(), emptySet(), findings)
        assertFalse(findings.any { it.rule == "SM048" })
    }

    @Test
    fun `wavelet OIT transmittance moment scalar output triggers SM055`() {
        val glsl = """
            #version 330
            #include <minecraft:oit.glsl>
            #ifdef OIT_TRANSMITTANCE
            layout(location = 0) out float transmittance;
            #endif
            void main() {}
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/core/terrain.fsh", "terrain.fsh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        PipelineCompiler.checkSingleStage(file, src, v26_3, setOf("minecraft:oit.glsl"), findings)
        val sm055 = findings.firstOrNull { it.rule == "SM055" }
        assertNotNull(sm055)
        assertTrue(sm055!!.message.contains("OIT_TRANSMITTANCE"))
    }

    @Test
    fun `wavelet OIT custom frag depth without bounds accurate triggers SM055`() {
        val glsl = """
            #version 330
            #include <minecraft:oit.glsl>
            #ifdef OIT_DEPTH_BOUNDS
            layout(location = 0) out vec4 outColor;
            #endif
            void main() {
                gl_FragDepth = 0.5;
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/core/custom_oit.fsh", "custom_oit.fsh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        PipelineCompiler.checkSingleStage(file, src, v26_3, setOf("minecraft:oit.glsl"), findings)
        val sm055 = findings.firstOrNull { it.rule == "SM055" && it.severity == Severity.WARNING }
        assertNotNull(sm055)
        assertTrue(sm055!!.message.contains("gl_FragDepth"))
    }

    @Test
    fun `spirv validator flags legacy built-ins under SM057`() {
        val glsl = """
            #version 330
            uniform mat4 gl_ModelViewMatrix;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/core/legacy.fsh", "legacy.fsh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SpirvValidator.checkFile(file, src, v26_3.id, findings)
        val rules = findings.map { it.rule }
        assertTrue(rules.contains("SM057"))
        assertTrue(findings.any { it.message.contains("gl_FragColor") })
        assertTrue(findings.any { it.message.contains("gl_ModelViewMatrix") })
        assertTrue(findings.any { it.message.contains("varying") })
    }

    @Test
    fun `spirv validator flags naked uniforms under SM057`() {
        val glsl = """
            #version 450
            layout(location = 0) in vec2 inTexCoord;
            layout(location = 0) out vec4 outColor;
            uniform vec4 ColorModulator;
            void main() {
                outColor = ColorModulator;
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/core/naked.fsh", "naked.fsh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SpirvValidator.checkFile(file, src, v26_3.id, findings)
        val sm057 = findings.firstOrNull { it.rule == "SM057" && it.message.contains("ColorModulator") }
        assertNotNull(sm057)
    }

    @Test
    fun `spirv validator flags missing layout locations and bindings under SM058`() {
        val glsl = """
            #version 450
            in vec3 normal;
            out vec4 color;
            uniform sampler2D DiffuseSampler;
            layout(std140) uniform MatrixBlock {
                mat4 ModelViewMat;
            };
            void main() {
                color = texture(DiffuseSampler, normal.xy);
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/core/unbound.fsh", "unbound.fsh", glsl)
        val src = GlslSource(glsl)
        val findings = mutableListOf<Finding>()

        SpirvValidator.checkFile(file, src, v26_3.id, findings)
        val sm058List = findings.filter { it.rule == "SM058" }
        assertEquals(4, sm058List.size)
        assertTrue(sm058List.any { it.message.contains("normal") })
        assertTrue(sm058List.any { it.message.contains("color") })
        assertTrue(sm058List.any { it.message.contains("DiffuseSampler") })
        assertTrue(sm058List.any { it.message.contains("MatrixBlock") })
    }

    @Test
    fun `spirv audit report generates clean metadata on compliant shader`() {
        val glsl = """
            #version 450
            layout(location = 0) in vec2 inTexCoord;
            layout(location = 0) out vec4 outColor;
            layout(binding = 0) uniform sampler2D DiffuseSampler;
            layout(binding = 1, std140) uniform MatrixBlock {
                mat4 MVP;
            };
            void main() {
                outColor = texture(DiffuseSampler, inTexCoord);
            }
        """.trimIndent()
        val file = PackFile("assets/minecraft/shaders/core/clean.fsh", "clean.fsh", glsl)
        val src = GlslSource(glsl)
        val report = SpirvValidator.auditFile(file, src, v26_3.id)

        assertTrue(report.isSpirvCompliant)
        assertEquals(0, report.findings.size)
        assertEquals(2, report.bindings.size)
        assertEquals("DiffuseSampler", report.bindings[0].name)
        assertEquals("MatrixBlock", report.bindings[1].name)
        assertEquals(2, report.interfaces.size)
    }

    @Test
    fun `wavelet OIT post effect target with 8-bit format triggers SM056`() {
        val tempDir = Files.createTempDirectory("oit-post-test")
        try {
            val mcmeta = tempDir.resolve("pack.mcmeta")
            Files.writeString(mcmeta, """
                {
                    "pack": {
                        "pack_format": 64,
                        "description": "Test"
                    }
                }
            """.trimIndent())
            val postDir = tempDir.resolve("assets/minecraft/post_effect")
            Files.createDirectories(postDir)
            val jsonFile = postDir.resolve("oit_chain.json")
            Files.writeString(jsonFile, """
                {
                    "targets": {
                        "oit_transmittance": {
                            "format": "rgba8"
                        }
                    },
                    "passes": []
                }
            """.trimIndent())

            val pack = PackSource.read(tempDir)
            val findings = ContractChecker(v26_3).check(pack)
            val sm056 = findings.firstOrNull { it.rule == "SM056" }
            assertNotNull(sm056)
            assertTrue(sm056!!.message.contains("rgba16f"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `spirv CLI command executes and outputs formatted report`() {
        val tempDir = Files.createTempDirectory("spirv-test")
        try {
            val shader = tempDir.resolve("test.fsh")
            Files.writeString(shader, """
                #version 450
                layout(location = 0) in vec2 inTexCoord;
                layout(location = 0) out vec4 outColor;
                layout(binding = 0) uniform sampler2D DiffuseSampler;
                void main() {
                    outColor = texture(DiffuseSampler, inTexCoord);
                }
            """.trimIndent())

            val res = SpirvCommand().test(listOf(shader.toString(), "--json"))
            assertEquals(0, res.statusCode, res.stderr)
            assertTrue(res.stdout.contains("DiffuseSampler"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `check command with --spirv flag reports dialect violations`() {
        val tempDir = Files.createTempDirectory("check-spirv-test")
        try {
            val mcmeta = tempDir.resolve("pack.mcmeta")
            Files.writeString(mcmeta, """
                {
                    "pack": {
                        "pack_format": 64,
                        "description": "Test"
                    }
                }
            """.trimIndent())
            val coreDir = tempDir.resolve("assets/minecraft/shaders/core")
            Files.createDirectories(coreDir)
            val shader = coreDir.resolve("rendertype_text.fsh")
            Files.writeString(shader, """
                #version 330
                varying vec4 vColor;
                void main() {
                    gl_FragColor = vColor;
                }
            """.trimIndent())

            val res = CheckCommand().test(listOf(tempDir.toString(), "--mc", "26.3", "--spirv", "--json"))
            assertEquals(1, res.statusCode)
            assertTrue(res.stdout.contains("SM057"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
