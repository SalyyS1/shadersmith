package com.salyvn.shadersmith.report

import com.salyvn.shadersmith.core.BuildInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Metadata for a diagnostic rule emitted into OASIS SARIF v2.1.0 `tool.driver.rules`. */
data class RuleMeta(
    val id: String,
    val name: String,
    val shortDescription: String,
    val defaultLevel: String,
)

object RuleCatalog {
    private val BY_ID: Map<String, RuleMeta> = listOf(
        RuleMeta("SM001", "ObsoleteCoreProgramJson", "Obsolete pre-1.21.5 shaders/core/*.json program definition", "warning"),
        RuleMeta("SM002", "ObsoletePostProgramJson", "Obsolete pre-1.21.5 shaders/post/*.json program config", "warning"),
        RuleMeta("SM010", "OverridesVanillaInclude", "Pack overrides a shared vanilla GLSL include file", "note"),
        RuleMeta("SM011", "UnknownVanillaShaderOverride", "Shader under assets/minecraft/shaders has no matching vanilla entry", "warning"),
        RuleMeta("SM020", "IncludeDeclaresVersion", "GLSL include file declares #version where forbidden (26.3+)", "error"),
        RuleMeta("SM021", "MissingGlslVersion", "Main GLSL shader is missing a #version directive", "error"),
        RuleMeta("SM022", "MismatchedGlslVersion", "GLSL #version differs from target Minecraft dialect", "warning"),
        RuleMeta("SM030", "WrongIncludeDirective", "GLSL include directive (#moj_import vs #include) does not match target version", "error"),
        RuleMeta("SM031", "MissingVanillaInclude", "GLSL shader includes a non-existent minecraft: include file", "error"),
        RuleMeta("SM032", "MissingCustomInclude", "GLSL shader includes a custom-namespaced file missing from the pack", "error"),
        RuleMeta("SM033", "CircularIncludeChain", "Circular GLSL #include / #moj_import dependency chain", "error"),
        RuleMeta("SM040", "UniformBlockMemberOrderMismatch", "Positional std140 uniform block members are out of order", "error"),
        RuleMeta("SM041", "UniformBlockSchemaMismatch", "Positional std140 uniform block members differ from vanilla contract", "error"),
        RuleMeta("SM042", "InvalidSsboLayoutOrDialect", "GLSL std430 or shader storage buffer violates OpenGL dialect/extension rules", "error"),
        RuleMeta("SM043", "InvalidSsboUnsizedArrayOrBinding", "SSBO unsized runtime array placement or buffer binding index is invalid", "error"),
        RuleMeta("SM044", "SsboQualifierAccessConflict", "Shader reads writeonly SSBO or mutates readonly SSBO member", "error"),
        RuleMeta("SM045", "Std430Vec3PaddingHazard", "SSBO std430 vec3 array or struct tail introduces 4-byte alignment padding trap", "warning"),
        RuleMeta("SM050", "MissingInterfaceLayoutLocation", "File-scope in/out variable lacks explicit layout(location = N) on 26.3+", "error"),
        RuleMeta("SM051", "LegacyGlVertexId", "Shader uses legacy gl_VertexID instead of gl_VertexIndex on 26.3+", "error"),
        RuleMeta("SM052", "CrossStageInterfaceMismatch", "Paired vertex and fragment shaders disagree on layout(location = N) type or interpolation", "error"),
        RuleMeta("SM053", "InvalidInterfaceLocationOrInterpolation", "Duplicate interface location, out-of-range slot, or non-flat integer fragment input", "error"),
        RuleMeta("SM054", "OitPipelineStateConflict", "Minecraft 26.3 Wavelet OIT pipeline state attachment conflict or unhooked override", "error"),
        RuleMeta("SM060", "LegacyPostEffectProgramPass", "Post-effect pass uses pre-1.21.5 'program' key instead of direct shaders", "error"),
        RuleMeta("SM061", "LegacyPostEffectUniformList", "Post-effect pass uses 1.21.5 list uniforms instead of uniform-block map", "error"),
        RuleMeta("SM062", "StalePostPassVertexShader", "Post-effect pass references a missing or outdated vanilla vertex shader", "warning"),
        RuleMeta("SM063", "RemovedTransparencyPostChain", "Post-effect targets minecraft:transparency removed in 26.3", "warning"),
        RuleMeta("SM064", "MissingPostPassShaderFile", "Post-effect pass references a missing vertex or fragment shader", "error"),
        RuleMeta("SM065", "InvalidPostEffectTargetGraph", "Post-effect pass references an undeclared or unwritten internal render target", "error"),
        RuleMeta("SM066", "PostPassSamplerMismatch", "Post-effect pass inputs and shader uniform sampler declarations disagree", "error"),
        RuleMeta("SM067", "PostPassUniformBlockMismatch", "Post-effect pass uniform block values disagree with shader std140 layout", "error"),
        RuleMeta("SM068", "MalformedPostEffectJson", "Post-effect JSON file cannot be parsed", "error"),
        RuleMeta("SM069", "PostPassTextureFormatOrFeedbackError", "Post-effect pass has a read/write feedback loop or incompatible texture format/sampler binding", "error"),
        RuleMeta("SM070", "UnreadablePackMcmeta", "pack.mcmeta is unreadable or lacks a 'pack' object", "error"),
        RuleMeta("SM071", "MissingPackFormat", "pack.mcmeta declares no pack format", "error"),
        RuleMeta("SM072", "PackFormatRangeMismatch", "Target Minecraft pack format falls outside pack.mcmeta declared range", "warning"),
        RuleMeta("SM073", "MissingMinMaxFormat", "Pack targeting format > 64 lacks min_format and max_format", "error"),
        RuleMeta("SM074", "EmptyOrMissingOverlayDirectory", "pack.mcmeta overlay entry points to a missing or empty directory", "warning"),
        RuleMeta("SM075", "InvalidPackMcmetaOverlayOrRange", "pack.mcmeta contains inverted format bounds or invalid overlay directory name", "error"),
        RuleMeta("SM076", "InvalidOrUnusedPostTargetConfig", "Post-effect internal target or texture input has invalid metadata or is never sampled", "warning"),
    ).associateBy { it.id }

    fun get(ruleId: String): RuleMeta = BY_ID[ruleId] ?: RuleMeta(
        id = ruleId,
        name = ruleId,
        shortDescription = "ShaderSmith contract diagnostic $ruleId",
        defaultLevel = "warning",
    )
}

/** The console report: one line per finding, grouped by version, worst first. */
object ConsoleReporter {
    fun lines(findings: List<Finding>, packName: String): List<String> {
        val out = mutableListOf<String>()
        if (findings.isEmpty()) {
            out += "== $packName: nothing to report"
            return out
        }
        findings.groupBy { it.version }.forEach { (version, forVersion) ->
            val errors = forVersion.count { it.severity == Severity.ERROR }
            val warnings = forVersion.count { it.severity == Severity.WARNING }
            out += "== $packName on $version: $errors error(s), $warnings warning(s)"
            forVersion.forEach { finding ->
                val where = finding.line?.let { "${finding.file}:$it" } ?: finding.file
                out += "   ${finding.severity.label.padEnd(7)} ${finding.rule}  $where"
                out += "           ${finding.message}"
                finding.fix?.let { out += "           fix: ${describeFix(it)}" }
            }
        }
        return out
    }

    internal fun describeFix(fix: Fix): String = when (fix) {
        is Fix.ReplaceLine -> if (fix.with.isEmpty()) "delete line ${fix.line}" else "line ${fix.line} -> '${fix.with}'"
        is Fix.DeleteFile -> "delete the file (${fix.reason})"
        is Fix.RewriteFile -> "rewrite file to match target version contract"
    }
}

private val PRETTY_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** OASIS SARIF v2.1.0 report with full driver rule descriptors for GitHub Code Scanning. */
object SarifReporter {
    private const val DOCS_URI = "https://github.com/SalyyS1/shadersmith#diagnostic-rules-reference"

    fun render(findings: List<Finding>): String {
        val ruleIds = findings.map { it.rule }.distinct().sorted()
        val sarif = buildJsonObject {
            put("\$schema", "https://json.schemastore.org/sarif-2.1.0.json")
            put("version", "2.1.0")
            put("runs", buildJsonArray {
                add(buildJsonObject {
                    putJsonObject("tool") {
                        putJsonObject("driver") {
                            put("name", "shadersmith")
                            put("version", BuildInfo.version)
                            put("informationUri", "https://github.com/SalyyS1/shadersmith")
                            put("rules", buildJsonArray {
                                ruleIds.forEach { ruleId ->
                                    val meta = RuleCatalog.get(ruleId)
                                    add(buildJsonObject {
                                        put("id", meta.id)
                                        put("name", meta.name)
                                        putJsonObject("shortDescription") { put("text", meta.shortDescription) }
                                        putJsonObject("defaultConfiguration") { put("level", meta.defaultLevel) }
                                        put("helpUri", DOCS_URI)
                                    })
                                }
                            })
                        }
                    }
                    put("results", buildJsonArray {
                        findings.forEach { finding ->
                            val cleanUri = finding.file.replace('\\', '/').trimStart('/')
                            add(buildJsonObject {
                                put("ruleId", finding.rule)
                                put("level", finding.severity.sarif)
                                putJsonObject("message") { put("text", "[${finding.version}] ${finding.message}") }
                                put("locations", buildJsonArray {
                                    add(buildJsonObject {
                                        putJsonObject("physicalLocation") {
                                            putJsonObject("artifactLocation") {
                                                put("uri", cleanUri)
                                                put("uriBaseId", "%SRCROOT%")
                                            }
                                            finding.line?.let { line ->
                                                putJsonObject("region") { put("startLine", line) }
                                            }
                                        }
                                    })
                                })
                                putJsonObject("partialFingerprints") {
                                    put("primaryLocationLineHash", "${finding.rule}:${finding.version}:$cleanUri:${finding.line ?: 0}")
                                }
                                putJsonObject("properties") {
                                    put("minecraftVersion", finding.version)
                                    put("fixable", finding.fix != null)
                                    finding.fix?.let { put("suggestedFix", ConsoleReporter.describeFix(it)) }
                                }
                            })
                        }
                    })
                })
            })
        }
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), sarif) + "\n"
    }
}

/** Plain JSON for anything that is not a SARIF consumer. */
object JsonReporter {
    fun render(findings: List<Finding>): String {
        val array: JsonArray = buildJsonArray {
            findings.forEach { finding ->
                add(buildJsonObject {
                    put("rule", finding.rule)
                    put("severity", finding.severity.label)
                    put("version", finding.version)
                    put("file", finding.file)
                    finding.line?.let { put("line", it) } ?: put("line", JsonPrimitive(null as String?))
                    put("message", finding.message)
                    put("fixable", finding.fix != null)
                })
            }
        }
        return PRETTY_JSON.encodeToString(JsonArray.serializer(), array) + "\n"
    }
}
