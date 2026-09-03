package com.salyvn.shadersmith.scaffold

import com.salyvn.shadersmith.contract.VersionContract

/** One uniform the effect exposes: `Strength:float=0.6`. */
data class UniformSpec(val name: String, val type: String, val value: List<Double>) {
    companion object {
        private val ARITY = mapOf("float" to 1, "int" to 1, "vec2" to 2, "vec3" to 3, "vec4" to 4)

        /** Parses `Name:type=v` or `Name:type=v,v,v`; the default for a missing value is zeros. */
        fun parse(text: String): UniformSpec {
            val (head, valueText) = text.split("=", limit = 2).let { it[0] to it.getOrNull(1) }
            val (name, type) = head.split(":", limit = 2).let { parts ->
                if (parts.size != 2) throw IllegalArgumentException("'$text' is not Name:type[=value]")
                parts[0].trim() to parts[1].trim()
            }
            val arity = ARITY[type] ?: throw IllegalArgumentException(
                "'$type' is not a uniform type this tool writes. Known: ${ARITY.keys.joinToString(", ")}.",
            )
            if (!Regex("[A-Za-z_]\\w*").matches(name)) throw IllegalArgumentException("'$name' is not a GLSL identifier")
            val values = valueText?.split(",")?.map { part ->
                part.trim().toDoubleOrNull() ?: throw IllegalArgumentException("'$part' in '$text' is not a number")
            } ?: List(arity) { 0.0 }
            if (values.size != arity) {
                throw IllegalArgumentException("$type takes $arity value(s), '$text' gives ${values.size}")
            }
            return UniformSpec(name, type, values)
        }
    }

    fun glslDeclaration(): String = "    $type $name;"

    fun jsonValue(): String = if (value.size == 1) number(value[0]) else value.joinToString(", ", "[ ", " ]") { number(it) }

    private fun number(d: Double): String = if (d == d.toLong().toDouble()) "${d.toLong()}.0" else d.toString()
}

/** A file the scaffold writes. */
data class ScaffoldFile(val relative: String, val text: String)

/**
 * Writes a working post effect for a version: the definition, the fragment shader, and the things
 * around it that are easy to forget.
 *
 * The shape follows vanilla's own `post_effect/invert.json` on the target version — one pass from
 * `minecraft:main` into a swap target and a blit back — because that is the shape known to load. The
 * uniform block the effect declares is named after the effect, so two effects from two packs never claim
 * the same block name.
 */
class PostEffectScaffold(
    private val contract: VersionContract,
    private val namespace: String,
    private val id: String,
    private val uniforms: List<UniformSpec>,
    private val alwaysOn: Boolean,
) {
    init {
        require(Regex("[a-z0-9_.-]+").matches(namespace)) { "'$namespace' is not a resource namespace" }
        require(Regex("[a-z0-9_./-]+").matches(id)) { "'$id' is not a resource path" }
        require(contract.postUniformsAreBlocks) {
            "${contract.id} predates the post effect format this tool writes (1.21.6 and newer)."
        }
    }

    private val blockName: String = id.split(Regex("[^A-Za-z0-9]")).joinToString("") { part ->
        part.replaceFirstChar { it.uppercase() }
    } + "Config"

    fun files(): List<ScaffoldFile> = buildList {
        add(ScaffoldFile("assets/$namespace/post_effect/$id.json", definition()))
        add(ScaffoldFile("assets/$namespace/shaders/post/$id.fsh", fragment()))
        if (alwaysOn) add(ScaffoldFile("assets/minecraft/post_effect/end_of_frame.json", endOfFrame()))
        add(ScaffoldFile("posteffect-commands.txt", commands()))
    }

    /** The definition. Uniforms go in the map form that 1.21.6 introduced; a `swap` target and two passes. */
    private fun definition(): String {
        val uniformsJson = if (uniforms.isEmpty()) {
            ""
        } else {
            val entries = uniforms.joinToString(",\n") { u ->
                """                    { "name": "${u.name}", "type": "${u.type}", "value": ${u.jsonValue()} }"""
            }
            """,
            "uniforms": {
                "$blockName": [
$entries
                ]
            }"""
        }
        return """
{
    "targets": {
        "swap": {}
    },
    "passes": [
        {
            "vertex_shader": "${contract.postPassVertexShader}",
            "fragment_shader": "$namespace:post/$id",
            "inputs": [
                { "sampler_name": "In", "target": "minecraft:main" }
            ],
            "output": "swap"$uniformsJson
        },
        {
            "vertex_shader": "${contract.postPassVertexShader}",
            "fragment_shader": "minecraft:post/blit",
            "inputs": [
                { "sampler_name": "In", "target": "swap" }
            ],
            "output": "minecraft:main",
            "uniforms": {
                "BlitConfig": [
                    { "name": "ColorModulate", "type": "vec4", "value": [ 1.0, 1.0, 1.0, 1.0 ] }
                ]
            }
        }
    ]
}
""".trimStart()
    }

    /**
     * The fragment shader, in the dialect the version compiles. `SamplerInfo` is the block the engine
     * binds for every post pass with a sampler input named `In`; the effect's own block follows it.
     */
    private fun fragment(): String {
        val modern = contract.includeDirective == "include"
        val version = contract.glslVersion ?: 330
        val block = if (uniforms.isEmpty()) "" else {
            "layout(std140) uniform $blockName {\n" + uniforms.joinToString("\n") { it.glslDeclaration() } + "\n};\n\n"
        }
        val inDecl = if (modern) "layout(location = 0) in vec2 texCoord;" else "in vec2 texCoord;"
        val outDecl = if (modern) "layout(location = 0) out vec4 fragColor;" else "out vec4 fragColor;"
        val extension = if (modern) "#extension GL_ARB_separate_shader_objects : require\n" else ""
        val firstUniform = uniforms.firstOrNull { it.type == "float" }?.name
        val body = if (firstUniform != null) {
            "    vec4 color = texture(InSampler, texCoord);\n" +
                "    // $firstUniform is yours to use; this mixes towards the inverse so the effect is visible at once.\n" +
                "    fragColor = vec4(mix(color.rgb, 1.0 - color.rgb, $firstUniform), 1.0);"
        } else {
            "    vec4 color = texture(InSampler, texCoord);\n" +
                "    fragColor = vec4(color.rgb, 1.0);"
        }
        return """
#version $version
$extension
uniform sampler2D InSampler;

$inDecl

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

$block$outDecl

void main() {
$body
}
""".trimStart().replace("\n\n\n", "\n\n")
    }

    /** `minecraft:end_of_frame` runs whenever the pack is loaded, last pack listed wins. */
    private fun endOfFrame(): String = definition()

    private fun commands(): String = """
# Trigger the effect for a player from the server console or a command block (26.3 and newer):
posteffect add @s $namespace:$id
posteffect remove @s $namespace:$id
posteffect clear @s
posteffect list @s
#
# The effect lives in the client's resource pack, so the server cannot tell whether it applied.
""".trimStart()
}
