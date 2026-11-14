package com.salyvn.shadersmith.contract

import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fixture under src/test/resources/oldpack is a pack written against 26.2 in the shape plugins
 * actually ship: a text shader with imports, a gui shader with an inlined uniform block, an overridden
 * include, a stale program JSON and a hook into the transparency chain.
 */
class ContractCheckerTest {

    private val dataset = ContractDataset.load()
    private val pack = PackSource.read(Path.of("src/test/resources/oldpack"))

    private fun check(version: String): List<Finding> = ContractChecker(dataset.resolve(version)).check(pack)

    private fun List<Finding>.rules(): List<String> = map { it.rule }.distinct().sorted()

    @Test
    fun `on the version it was written for, only the stale LineWidth is an error`() {
        val findings = check("26.2")
        val errors = findings.filter { it.severity == Severity.ERROR }

        assertEquals(listOf("SM041"), errors.rules())
        assertTrue(errors.single().message.contains("LineWidth"))
        assertTrue(errors.single().file.endsWith("gui.vsh"))
    }

    @Test
    fun `on 1_21_10 the same block is exactly right and there are no errors`() {
        val findings = check("1.21.10")

        assertTrue(findings.none { it.severity == Severity.ERROR }, findings.joinToString("\n") { it.message })
    }

    @Test
    fun `on 26_3 every silent break is named`() {
        val rules = check("26.3-pre-1").filter { it.severity == Severity.ERROR }.rules()

        assertTrue("SM041" in rules, "block members differ")
        assertTrue("SM030" in rules, "#moj_import must become #include")
        assertTrue("SM020" in rules, "#version in an include")
        assertTrue("SM050" in rules, "bare in/out without layout(location)")
        assertTrue("SM051" in rules, "gl_VertexID rename")
    }

    @Test
    fun `a stale program json is a warning with a delete fix on every version after 1_21_4`() {
        val stale = check("26.2").filter { it.rule == "SM001" }

        assertEquals(1, stale.size)
        assertEquals(Severity.WARNING, stale.single().severity)
        assertTrue(stale.single().file.endsWith("rendertype_text.json"))
        assertTrue(check("1.21.4").none { it.rule == "SM001" }, "on 1.21.4 the file is still read")
    }

    @Test
    fun `the transparency hook is flagged only where the chain is gone`() {
        assertTrue(check("26.2").none { it.rule == "SM063" })
        assertEquals(1, check("26.3-pre-1").count { it.rule == "SM063" })
    }

    @Test
    fun `the mcmeta range is checked against the version`() {
        assertTrue(check("26.2").none { it.rule == "SM072" }, "88 is inside 63..88")
        assertEquals(1, check("26.3-pre-1").count { it.rule == "SM072" }, "97 is outside it")
    }

    @Test
    fun `replacing a vanilla include is a note, not an error`() {
        val note = check("26.2").single { it.rule == "SM010" }

        assertEquals(Severity.NOTE, note.severity)
        assertTrue(note.file.endsWith("include/fog.glsl"))
    }

    @Test
    fun `findings come worst first`() {
        val findings = check("26.3-pre-1")
        val severities = findings.map { it.severity.ordinal }

        assertEquals(severities.sorted(), severities)
    }

    @Test
    fun `resolves pack mcmeta overlays per format and checks SM032 SM064 SM074`(@TempDir dir: Path) {
        val packDir = dir.resolve("overlay-pack")
        Files.createDirectories(packDir.resolve("assets/myfx/shaders/core"))
        Files.createDirectories(packDir.resolve("assets/myfx/post_effect"))
        Files.createDirectories(packDir.resolve("overlay_26_3/assets/myfx/shaders/include"))

        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """
            {
              "pack": { "min_format": 63, "max_format": 99, "pack_format": 63, "supported_formats": [63, 64] },
              "overlays": {
                "entries": [
                  { "directory": "overlay_26_3", "min_format": 90, "max_format": 99 },
                  { "directory": "missing_overlay", "formats": [80, 89] }
                ]
              }
            }
            """.trimIndent(),
        )
        // Shader includes myfx:math.glsl -> missing on 26.2 (format 88), satisfied on 26.3-pre-1 (format 97) via overlay_26_3!
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/core/custom.fsh"),
            "#version 330\n#include <myfx:math.glsl>\nlayout(location = 0) out vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n",
        )
        Files.writeString(
            packDir.resolve("overlay_26_3/assets/myfx/shaders/include/math.glsl"),
            "float helper() { return 1.0; }\n",
        )
        Files.writeString(
            packDir.resolve("assets/myfx/post_effect/broken.json"),
            """
            {
              "targets": {},
              "passes": [
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "myfx:post/missing_frag"
                }
              ]
            }
            """.trimIndent(),
        )

        val src = PackSource.read(packDir)
        val on262 = ContractChecker(dataset.resolve("26.2")).check(src)
        assertTrue(on262.any { it.rule == "SM032" }, "Missing myfx:math.glsl on 26.2 before overlay activates")
        assertTrue(on262.any { it.rule == "SM064" }, "Missing myfx:post/missing_frag.fsh flagged")
        assertTrue(on262.any { it.rule == "SM074" }, "Empty/missing overlay directory flagged")

        val on263 = ContractChecker(dataset.resolve("26.3-pre-1")).check(src)
        assertTrue(on263.none { it.rule == "SM032" }, "overlay_26_3 provides myfx:math.glsl on 26.3-pre-1")
    }

    @Test
    fun `detects circular GLSL include chains with SM033`(@TempDir dir: Path) {
        val packDir = dir.resolve("cycle-pack")
        Files.createDirectories(packDir.resolve("assets/myfx/shaders/include"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """{ "pack": { "min_format": 90, "max_format": 99 } }""",
        )
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/include/a.glsl"),
            "#include <myfx:b.glsl>\nfloat a() { return 1.0; }\n",
        )
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/include/b.glsl"),
            "#include <myfx:a.glsl>\nfloat b() { return 2.0; }\n",
        )

        val findings = ContractChecker(dataset.resolve("26.3")).check(PackSource.read(packDir))
        val cycle = findings.filter { it.rule == "SM033" }
        assertEquals(1, cycle.size)
        assertEquals(Severity.ERROR, cycle.single().severity)
        assertTrue(cycle.single().message.contains("assets/myfx/shaders/include/a.glsl"))
        assertTrue(cycle.single().message.contains("assets/myfx/shaders/include/b.glsl"))
    }

    @Test
    fun `cross checks post effect targets samplers and std140 uniform blocks on 26_3`(@TempDir dir: Path) {
        val packDir = dir.resolve("postfx-pack")
        Files.createDirectories(packDir.resolve("assets/myfx/post_effect"))
        Files.createDirectories(packDir.resolve("assets/myfx/shaders/post"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """{ "pack": { "min_format": 90, "max_format": 99 } }""",
        )
        Files.writeString(
            packDir.resolve("assets/myfx/shaders/post/tint.fsh"),
            """
            #version 330
            uniform sampler2D InSampler;
            uniform sampler2D MaskSampler;
            layout(location = 0) in vec2 texCoord;
            layout(std140) uniform TintConfig {
                vec3 TintColor;
                float Strength;
            };
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = texture(InSampler, texCoord) * texture(MaskSampler, texCoord) * vec4(TintColor, Strength);
            }
            """.trimIndent(),
        )
        Files.writeString(
            packDir.resolve("assets/myfx/post_effect/tint.json"),
            """
            {
              "targets": {
                "swap": {}
              },
              "passes": [
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "myfx:post/tint",
                  "inputs": [
                    { "sampler_name": "In", "target": "swap" },
                    { "sampler_name": "Unused", "target": "minecraft:main" }
                  ],
                  "output": "undeclared_buf",
                  "uniforms": {
                    "TintConfig": [
                      { "name": "Strength", "type": "float", "value": 0.5 },
                      { "name": "TintColor", "type": "vec3", "value": [1.0, 0.0, 0.0] }
                    ],
                    "GhostBlock": [
                      { "name": "Foo", "type": "float", "value": 1.0 }
                    ]
                  }
                },
                {
                  "vertex_shader": "minecraft:post/rotscale",
                  "fragment_shader": "minecraft:post/blit"
                }
              ]
            }
            """.trimIndent(),
        )

        val findings = ContractChecker(dataset.resolve("26.3")).check(PackSource.read(packDir))

        // minecraft:post/rotscale is a valid vanilla 26.3 vertex shader and must not trigger SM062
        assertTrue(findings.none { it.rule == "SM062" }, "minecraft:post/rotscale exists in vanilla 26.3")
        // SM065: reading swap before write (warning) + writing undeclared_buf (error)
        assertTrue(findings.any { it.rule == "SM065" && it.severity == Severity.WARNING && it.message.contains("swap") })
        assertTrue(findings.any { it.rule == "SM065" && it.severity == Severity.ERROR && it.message.contains("undeclared_buf") })
        // SM066: MaskSampler unbound (error) + UnusedSampler undeclared in tint.fsh (warning)
        assertTrue(findings.any { it.rule == "SM066" && it.severity == Severity.ERROR && it.message.contains("MaskSampler") })
        assertTrue(findings.any { it.rule == "SM066" && it.severity == Severity.WARNING && it.message.contains("Unused") })
        // SM067: TintConfig member order mismatch (error) + GhostBlock undeclared (warning)
        assertTrue(findings.any { it.rule == "SM067" && it.severity == Severity.ERROR && it.message.contains("TintConfig") })
        assertTrue(findings.any { it.rule == "SM067" && it.severity == Severity.WARNING && it.message.contains("GhostBlock") })
    }

    @Test
    fun `reports malformed post effect JSON as SM068 and invalid mcmeta bounds or overlay dir as SM075`(@TempDir dir: Path) {
        val packDir = dir.resolve("broken-meta-pack")
        Files.createDirectories(packDir.resolve("assets/myfx/post_effect"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """
            {
              "pack": { "min_format": 99, "max_format": 88 },
              "overlays": {
                "entries": [
                  { "directory": "nested/bad_dir", "min_format": 88, "max_format": 97 },
                  { "directory": "inverted_overlay", "min_format": 97, "max_format": 88 }
                ]
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            packDir.resolve("assets/myfx/post_effect/malformed.json"),
            "{ \"targets\": { \"swap\": {} ",
        )

        val findings = ContractChecker(dataset.resolve("26.3")).check(PackSource.read(packDir))
        assertTrue(findings.any { it.rule == "SM068" && it.severity == Severity.ERROR })
        assertEquals(3, findings.count { it.rule == "SM075" && it.severity == Severity.ERROR })
    }
}
