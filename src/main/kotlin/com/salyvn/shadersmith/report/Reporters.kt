package com.salyvn.shadersmith.report

import com.salyvn.shadersmith.core.BuildInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json

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
                finding.fix?.let { out += "           fix: ${describe(it)}" }
            }
        }
        return out
    }

    private fun describe(fix: Fix): String = when (fix) {
        is Fix.ReplaceLine -> if (fix.with.isEmpty()) "delete line ${fix.line}" else "line ${fix.line} -> '${fix.with}'"
        is Fix.DeleteFile -> "delete the file (${fix.reason})"
        is Fix.RewriteFile -> "rewrite the uniform block to the version's layout"
    }
}

/** SARIF 2.1.0, so a CI run can show findings inline on a pull request. */
object SarifReporter {
    fun render(findings: List<Finding>): String {
        val rules = findings.map { it.rule }.distinct().sorted()
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
                                rules.forEach { rule -> add(buildJsonObject { put("id", rule) }) }
                            })
                        }
                    }
                    put("results", buildJsonArray {
                        findings.forEach { finding ->
                            add(buildJsonObject {
                                put("ruleId", finding.rule)
                                put("level", finding.severity.sarif)
                                putJsonObject("message") { put("text", "[${finding.version}] ${finding.message}") }
                                put("locations", buildJsonArray {
                                    add(buildJsonObject {
                                        putJsonObject("physicalLocation") {
                                            putJsonObject("artifactLocation") { put("uri", finding.file) }
                                            finding.line?.let { line ->
                                                putJsonObject("region") { put("startLine", line) }
                                            }
                                        }
                                    })
                                })
                                putJsonObject("partialFingerprints") {
                                    put("primaryLocationLineHash", "${finding.rule}:${finding.file}:${finding.line ?: 0}")
                                }
                            })
                        }
                    })
                })
            })
        }
        return Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonObject.serializer(), sarif) + "\n"
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
        return Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonArray.serializer(), array) + "\n"
    }
}
