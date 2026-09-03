package com.salyvn.shadersmith.contract

import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Fix
import com.salyvn.shadersmith.report.Severity

/**
 * Checks one pack against one version's contract.
 *
 * Each rule below corresponds to a change that was verified by reading the vanilla files on both sides
 * of it. They are ordered roughly by how badly they fail: a pack that is refused outright is a nuisance,
 * a pack that loads and quietly reads the wrong uniform is a bug report three weeks later.
 */
class ContractChecker(private val contract: VersionContract) {

    fun check(pack: PackSource): List<Finding> {
        val findings = mutableListOf<Finding>()
        pack.files.forEach { file ->
            when {
                file.shaderDirectory == "core" && file.fileName.endsWith(".json") -> checkCoreJson(file, findings)
                file.shaderDirectory == "post" && file.fileName.endsWith(".json") -> checkPostJson(file, findings)
                file.isShaderSource -> checkSource(file, GlslSource(file.text), findings)
                file.isPostEffectDefinition -> checkPostEffect(file, findings)
            }
        }
        pack.packMeta?.let { checkPackMeta(it, findings) }
        return findings.sortedWith(compareBy({ it.severity.ordinal }, { it.file }, { it.line ?: 0 }))
    }

    /** `shaders/core/<program>.json` stopped being read at 1.21.5; it is dead weight or a stale intent. */
    private fun checkCoreJson(file: PackFile, out: MutableList<Finding>) {
        if (contract.coreProgramJson) return
        out += Finding(
            rule = "SM001",
            severity = Severity.WARNING,
            version = contract.id,
            file = file.relative,
            line = null,
            message = "Program definitions under shaders/core were removed in 1.21.5. This file is ignored " +
                "on ${contract.id}, and whatever it used to point a render type at no longer happens.",
            fix = Fix.DeleteFile("shaders/core/*.json is not read since 1.21.5"),
        )
    }

    /** `shaders/post/<name>.json` program configs went with 1.21.5 too. */
    private fun checkPostJson(file: PackFile, out: MutableList<Finding>) {
        if (contract.postEffectSchema == "program-ref" || contract.postEffectSchema == "legacy-shaders-post") return
        out += Finding(
            rule = "SM002",
            severity = Severity.WARNING,
            version = contract.id,
            file = file.relative,
            line = null,
            message = "Post program configs under shaders/post were removed in 1.21.5; a post effect names " +
                "its vertex_shader and fragment_shader directly. This file is ignored on ${contract.id}.",
            fix = Fix.DeleteFile("shaders/post/*.json is not read since 1.21.5"),
        )
    }

    private fun checkSource(file: PackFile, source: GlslSource, out: MutableList<Finding>) {
        checkOverridesVanilla(file, out)
        checkVersionLine(file, source, out)
        checkDirectives(file, source, out)
        checkBlocks(file, source, out)
        checkInterfaceLayout(file, source, out)
        checkVertexId(file, source, out)
    }

    /** A file under minecraft:shaders that vanilla does not have on this version is never loaded. */
    private fun checkOverridesVanilla(file: PackFile, out: MutableList<Finding>) {
        if (file.namespace != "minecraft") return
        val known = when (file.shaderDirectory) {
            "core" -> contract.coreProgramNames
            "include" -> contract.includeNames
            "post" -> contract.postPrograms.toSet()
            else -> return
        }
        if (file.fileName in known) {
            if (file.isGlslInclude) {
                out += Finding(
                    rule = "SM010",
                    severity = Severity.NOTE,
                    version = contract.id,
                    file = file.relative,
                    line = null,
                    message = "Replaces a vanilla include, which every vanilla program that includes it will " +
                        "now compile against. Two packs doing this to the same file cannot both win.",
                )
            }
            return
        }
        out += Finding(
            rule = "SM011",
            severity = Severity.WARNING,
            version = contract.id,
            file = file.relative,
            line = null,
            message = "There is no ${file.shaderDirectory}/${file.fileName} in vanilla ${contract.id}, so nothing " +
                "loads this file. It was probably written for a version that had it, or the name changed.",
        )
    }

    /**
     * Two rules about `#version`: the number must match what the version's own shaders declare, and from
     * 26.3 an include must not carry one at all, because the compiler wants it on the first line of the
     * main unit only.
     */
    private fun checkVersionLine(file: PackFile, source: GlslSource, out: MutableList<Finding>) {
        val declared = source.version
        if (file.isGlslInclude) {
            if (declared != null && !contract.includesCarryVersion) {
                out += Finding(
                    rule = "SM020",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.relative,
                    line = declared.second,
                    message = "An include must not declare #version on ${contract.id}; the directive belongs on the " +
                        "first line of the main shader only, and a second one fails compilation.",
                    fix = Fix.ReplaceLine(declared.second, ""),
                )
            }
            return
        }
        val expected = contract.glslVersion ?: return
        if (declared == null) {
            out += Finding(
                rule = "SM021",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.relative,
                line = 1,
                message = "No #version line. Vanilla ${contract.id} shaders declare #version $expected.",
            )
        } else if (declared.first != expected) {
            out += Finding(
                rule = "SM022",
                severity = Severity.WARNING,
                version = contract.id,
                file = file.relative,
                line = declared.second,
                message = "#version ${declared.first} where vanilla ${contract.id} uses #version $expected.",
                fix = Fix.ReplaceLine(declared.second, "#version $expected"),
            )
        }
    }

    /** `#moj_import` became `#include` at 26.3; the target syntax stayed the same. */
    private fun checkDirectives(file: PackFile, source: GlslSource, out: MutableList<Finding>) {
        val expected = contract.includeDirective ?: return
        source.includes.forEach { directive ->
            if (directive.directive != expected) {
                out += Finding(
                    rule = "SM030",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.relative,
                    line = directive.line,
                    message = "#${directive.directive} is not the include directive on ${contract.id}; it is " +
                        "#$expected. The file will not preprocess.",
                    fix = Fix.ReplaceLine(directive.line, "#$expected <${directive.target}>"),
                )
            }
            val target = directive.target.substringAfter(':')
            if (directive.target.startsWith("minecraft:") || !directive.target.contains(':')) {
                if (target !in contract.includeNames) {
                    out += Finding(
                        rule = "SM031",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = file.relative,
                        line = directive.line,
                        message = "Includes '${directive.target}', which vanilla ${contract.id} does not have. " +
                            "Known includes: ${contract.includeFiles.joinToString(", ")}.",
                    )
                }
            }
        }
    }

    /**
     * The rule that matters most. A `layout(std140) uniform` block is matched to the engine's buffer by
     * name, and its members by position. A block with the right names in the wrong order — or with a member
     * the version no longer has — compiles cleanly and reads the neighbouring bytes.
     */
    private fun checkBlocks(file: PackFile, source: GlslSource, out: MutableList<Finding>) {
        source.blocks.forEach { block ->
            val expected = contract.uniformBlocks[block.name] ?: return@forEach
            if (block.members == expected) return@forEach
            val declared = block.members.joinToString(", ") { "${it.type} ${it.name}" }
            val wanted = expected.joinToString(", ") { "${it.type} ${it.name}" }
            val sameSet = block.members.toSet() == expected.toSet()
            val message = if (sameSet) {
                "Uniform block ${block.name} has the right members in the wrong order for ${contract.id}. " +
                    "std140 is positional, so this compiles and reads the wrong data. Declared: $declared. " +
                    "Vanilla ${contract.id}: $wanted."
            } else {
                "Uniform block ${block.name} does not match vanilla ${contract.id}. Declared: $declared. " +
                    "Vanilla: $wanted. A member that does not exist reads garbage; a missing one shifts " +
                    "every member after it."
            }
            out += Finding(
                rule = if (sameSet) "SM040" else "SM041",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.relative,
                line = block.line,
                message = message,
                fix = Fix.RewriteFile { text -> rewriteBlock(text, block.name, expected) },
            )
        }
    }

    /** From 26.3, every `in`/`out` at file scope carries `layout(location = N)`; a bare one is refused. */
    private fun checkInterfaceLayout(file: PackFile, source: GlslSource, out: MutableList<Finding>) {
        if (contract.includeDirective != "include") return
        if (file.isGlslInclude) return
        source.unqualifiedInterfaceVariables.forEach { (declaration, line) ->
            out += Finding(
                rule = "SM050",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.relative,
                line = line,
                message = "'$declaration' has no layout(location = N). Vanilla ${contract.id} qualifies every " +
                    "in/out with an explicit location; the compiler requires it. Locations are assigned in " +
                    "declaration order starting at 0 per direction, which is not something a tool can " +
                    "safely guess for you.",
            )
        }
    }

    /** `gl_VertexID` became `gl_VertexIndex` at 26.3. */
    private fun checkVertexId(file: PackFile, source: GlslSource, out: MutableList<Finding>) {
        if (contract.includeDirective != "include") return
        source.vertexIdLines.forEach { line ->
            out += Finding(
                rule = "SM051",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.relative,
                line = line,
                message = "gl_VertexID is gl_VertexIndex on ${contract.id}.",
                fix = Fix.ReplaceLine(line, source.text.lines()[line - 1].replace("gl_VertexID", "gl_VertexIndex")),
            )
        }
    }

    /** Post effect definitions changed shape twice; the old shapes are read as errors, not ignored. */
    private fun checkPostEffect(file: PackFile, out: MutableList<Finding>) {
        val text = file.text
        if ("\"program\"" in text && contract.postEffectSchema != "program-ref") {
            out += Finding(
                rule = "SM060",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.relative,
                line = null,
                message = "A pass with \"program\" is the 1.21.2-1.21.4 shape. On ${contract.id} a pass names " +
                    "\"vertex_shader\" and \"fragment_shader\" directly.",
            )
        }
        if (contract.postUniformsAreBlocks && Regex("\"uniforms\"\\s*:\\s*\\[").containsMatchIn(text)) {
            out += Finding(
                rule = "SM061",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.relative,
                line = null,
                message = "\"uniforms\" is a list, which is the 1.21.5 shape. On ${contract.id} it is a map " +
                    "from uniform block name to a list of {name, type, value}.",
            )
        }
        Regex("\"vertex_shader\"\\s*:\\s*\"([^\"]+)\"").findAll(text).forEach { match ->
            val declared = match.groupValues[1]
            if (declared != contract.postPassVertexShader && declared.startsWith("minecraft:")) {
                out += Finding(
                    rule = "SM062",
                    severity = Severity.WARNING,
                    version = contract.id,
                    file = file.relative,
                    line = null,
                    message = "Pass vertex_shader '$declared'; vanilla ${contract.id} post passes use " +
                        "'${contract.postPassVertexShader}'. If '$declared' is not in the pack, the pass fails.",
                )
            }
        }
        val id = file.fileName.removeSuffix(".json")
        if (id == "transparency" && file.namespace == "minecraft" && "transparency" !in contract.postEffects) {
            out += Finding(
                rule = "SM063",
                severity = Severity.WARNING,
                version = contract.id,
                file = file.relative,
                line = null,
                message = "minecraft:transparency was removed in 26.3 along with the Fabulous transparency chain. " +
                    "Anything hooked into it no longer runs.",
                fix = Fix.DeleteFile("post_effect/transparency.json is not read since 26.3"),
            )
        }
    }

    /** The declared range has to include the version being checked, or none of the above matters. */
    private fun checkPackMeta(file: PackFile, out: MutableList<Finding>) {
        val range = PackMetaReader.read(file.text) ?: run {
            out += Finding("SM070", Severity.ERROR, contract.id, file.relative, null, "pack.mcmeta is not readable JSON with a \"pack\" section.")
            return
        }
        val min = range.effectiveMin
        val max = range.effectiveMax
        if (min == null || max == null) {
            out += Finding("SM071", Severity.ERROR, contract.id, file.relative, null, "pack.mcmeta declares no pack format at all.")
            return
        }
        if (contract.packFormat < min || contract.packFormat > max) {
            out += Finding(
                rule = "SM072",
                severity = Severity.WARNING,
                version = contract.id,
                file = file.relative,
                line = null,
                message = "pack.mcmeta claims formats $min..$max, and ${contract.id} is format ${contract.packFormat}. " +
                    "The client lists the pack as incompatible before any shader is read.",
            )
        }
        if (contract.packFormat > 64 && range.min == null) {
            out += Finding(
                rule = "SM073",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.relative,
                null,
                "Formats above 64 require min_format and max_format in pack.mcmeta; pack_format alone " +
                    "cannot claim ${contract.id}.",
            )
        }
    }

    private fun rewriteBlock(text: String, name: String, members: List<BlockMember>): String {
        val pattern = Regex("(layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+$name\\s*\\{)[^}]*(\\})")
        val body = members.joinToString("") { "\n    ${it.type} ${it.name};" } + "\n"
        return pattern.replace(text) { match -> match.groupValues[1] + body + match.groupValues[2] }
    }
}
