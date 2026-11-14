package com.salyvn.shadersmith.contract

import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Fix
import com.salyvn.shadersmith.report.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Checks one pack against one version's contract, resolving active `pack.mcmeta` overlays for the version's
 * pack format before checking shaders, include dependency graphs, and post-effect pass wiring.
 */
class ContractChecker(private val contract: VersionContract) {

    fun check(pack: PackSource): List<Finding> {
        val findings = mutableListOf<Finding>()
        val parsedMeta = pack.packMeta?.let { checkPackMeta(pack, it, findings) }
        val effective = pack.effectiveFiles(contract.packFormat, parsedMeta?.overlays ?: emptyList())
        val effectiveByPath = effective.associateBy { it.relative }
        val effectivePaths = effectiveByPath.keys
        val glslByPath = effective.filter { it.isShaderSource }.associate { it.relative to GlslSource(it.text) }

        checkIncludeCycles(effective, effectiveByPath, glslByPath, findings)

        effective.forEach { file ->
            when {
                file.shaderDirectory == "core" && file.fileName.endsWith(".json") -> checkCoreJson(file, findings)
                file.shaderDirectory == "post" && file.fileName.endsWith(".json") -> checkPostJson(file, findings)
                file.isShaderSource -> checkSource(file, glslByPath.getValue(file.relative), effectivePaths, findings)
                file.isPostEffectDefinition -> checkPostEffect(file, effectivePaths, glslByPath, findings)
            }
        }
        return findings.sortedWith(compareBy({ it.severity.ordinal }, { it.file }, { it.line ?: 0 }))
    }

    /** `shaders/core/<program>.json` stopped being read at 1.21.5; it is dead weight or a stale intent. */
    private fun checkCoreJson(file: PackFile, out: MutableList<Finding>) {
        if (contract.coreProgramJson) return
        out += Finding(
            rule = "SM001",
            severity = Severity.WARNING,
            version = contract.id,
            file = file.physicalRelative,
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
            file = file.physicalRelative,
            line = null,
            message = "Post program configs under shaders/post were removed in 1.21.5; a post effect names " +
                "its vertex_shader and fragment_shader directly. This file is ignored on ${contract.id}.",
            fix = Fix.DeleteFile("shaders/post/*.json is not read since 1.21.5"),
        )
    }

    private fun checkSource(
        file: PackFile,
        source: GlslSource,
        effectivePaths: Set<String>,
        out: MutableList<Finding>,
    ) {
        checkOverridesVanilla(file, out)
        checkVersionLine(file, source, out)
        checkDirectives(file, source, effectivePaths, out)
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
            "post" -> contract.postProgramNames
            else -> return
        }
        if (file.fileName in known) {
            if (file.isGlslInclude) {
                out += Finding(
                    rule = "SM010",
                    severity = Severity.NOTE,
                    version = contract.id,
                    file = file.physicalRelative,
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
            file = file.physicalRelative,
            line = null,
            message = "There is no ${file.shaderDirectory}/${file.fileName} in vanilla ${contract.id}, so nothing " +
                "loads this file. It was probably written for a version that had it, or the name changed.",
        )
    }

    private fun checkVersionLine(file: PackFile, source: GlslSource, out: MutableList<Finding>) {
        val declared = source.version
        if (file.isGlslInclude) {
            if (declared != null && !contract.includesCarryVersion) {
                out += Finding(
                    rule = "SM020",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
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
                file = file.physicalRelative,
                line = 1,
                message = "No #version line. Vanilla ${contract.id} shaders declare #version $expected.",
            )
        } else if (declared.first != expected) {
            out += Finding(
                rule = "SM022",
                severity = Severity.WARNING,
                version = contract.id,
                file = file.physicalRelative,
                line = declared.second,
                message = "#version ${declared.first} where vanilla ${contract.id} uses #version $expected.",
                fix = Fix.ReplaceLine(declared.second, "#version $expected"),
            )
        }
    }

    /** `#moj_import` became `#include` at 26.3; checks both vanilla and custom-namespaced include targets. */
    private fun checkDirectives(
        file: PackFile,
        source: GlslSource,
        effectivePaths: Set<String>,
        out: MutableList<Finding>,
    ) {
        val expected = contract.includeDirective ?: return
        source.includes.forEach { directive ->
            if (directive.directive != expected) {
                out += Finding(
                    rule = "SM030",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = directive.line,
                    message = "#${directive.directive} is not the include directive on ${contract.id}; it is " +
                        "#$expected. The file will not preprocess.",
                    fix = Fix.ReplaceLine(directive.line, "#$expected <${directive.target}>"),
                )
            }
            val target = directive.target.substringAfter(':')
            if (directive.target.startsWith("minecraft:") || !directive.target.contains(':')) {
                val customMinecraftInclude = "assets/minecraft/shaders/include/$target"
                val relativeSibling = "${file.relative.substringBeforeLast('/')}/$target"
                if (target !in contract.includeNames && customMinecraftInclude !in effectivePaths && relativeSibling !in effectivePaths) {
                    out += Finding(
                        rule = "SM031",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = directive.line,
                        message = "Includes '${directive.target}', which vanilla ${contract.id} does not have. " +
                            "Known includes: ${contract.includeFiles.joinToString(", ")}.",
                    )
                }
            } else {
                val ns = directive.target.substringBefore(':')
                val expectedEntry = "assets/$ns/shaders/include/$target"
                if (expectedEntry !in effectivePaths) {
                    out += Finding(
                        rule = "SM032",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = directive.line,
                        message = "Includes '${directive.target}', but '$expectedEntry' does not exist in the pack.",
                    )
                }
            }
        }
    }

    /** Detects direct or transitive include cycles across pack GLSL files (`a.glsl -> b.glsl -> a.glsl`). */
    private fun checkIncludeCycles(
        effective: List<PackFile>,
        effectiveByPath: Map<String, PackFile>,
        glslByPath: Map<String, GlslSource>,
        out: MutableList<Finding>,
    ) {
        val permanentlyVisited = mutableSetOf<String>()
        val reportedCycles = mutableSetOf<Set<String>>()
        val activeStack = mutableListOf<String>()

        fun dfs(currentPath: String) {
            val currentFile = effectiveByPath[currentPath] ?: return
            val source = glslByPath[currentPath] ?: return
            activeStack += currentPath
            for (directive in source.includes) {
                val nextPath = resolveIncludePath(currentPath, directive.target, glslByPath.keys) ?: continue
                val idx = activeStack.indexOf(nextPath)
                if (idx >= 0) {
                    val cycleNodes = activeStack.subList(idx, activeStack.size)
                    val signature = cycleNodes.toSet()
                    if (reportedCycles.add(signature)) {
                        val chain = (cycleNodes + nextPath).joinToString(" -> ")
                        out += Finding(
                            rule = "SM033",
                            severity = Severity.ERROR,
                            version = contract.id,
                            file = currentFile.physicalRelative,
                            line = directive.line,
                            message = "Circular GLSL include chain detected: $chain.",
                        )
                    }
                } else if (nextPath !in permanentlyVisited) {
                    dfs(nextPath)
                }
            }
            activeStack.removeAt(activeStack.lastIndex)
            permanentlyVisited += currentPath
        }

        effective.filter { it.isShaderSource }.forEach { file ->
            if (file.relative !in permanentlyVisited) {
                dfs(file.relative)
            }
        }
    }

    private fun resolveIncludePath(fromLogicalPath: String, target: String, knownGlslPaths: Set<String>): String? {
        if (target.contains(':')) {
            val ns = target.substringBefore(':')
            val rel = target.substringAfter(':')
            val candidate = "assets/$ns/shaders/include/$rel"
            return candidate.takeIf { it in knownGlslPaths }
        }
        val sibling = "${fromLogicalPath.substringBeforeLast('/')}/$target"
        if (sibling in knownGlslPaths) return sibling
        val mcInclude = "assets/minecraft/shaders/include/$target"
        return mcInclude.takeIf { it in knownGlslPaths }
    }

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
                file = file.physicalRelative,
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
                file = file.physicalRelative,
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
                file = file.physicalRelative,
                line = line,
                message = "gl_VertexID is gl_VertexIndex on ${contract.id}.",
                fix = Fix.ReplaceLine(line, source.text.lines()[line - 1].replace("gl_VertexID", "gl_VertexIndex")),
            )
        }
    }

    /**
     * Validates post-effect JSON syntax, schema era, vertex/fragment shader existence, internal/external
     * render target graph wiring, sampler bindings, and std140 uniform block contracts.
     */
    private fun checkPostEffect(
        file: PackFile,
        effectivePaths: Set<String>,
        glslByPath: Map<String, GlslSource>,
        out: MutableList<Finding>,
    ) {
        val text = file.text
        val id = file.fileName.removeSuffix(".json")
        if (id == "transparency" && file.namespace == "minecraft" && "transparency" !in contract.postEffects) {
            out += Finding(
                rule = "SM063",
                severity = Severity.WARNING,
                version = contract.id,
                file = file.physicalRelative,
                line = null,
                message = "minecraft:transparency was removed in 26.3 along with the Fabulous transparency chain. " +
                    "Anything hooked into it no longer runs.",
                fix = Fix.DeleteFile("post_effect/transparency.json is not read since 26.3"),
            )
        }

        val root = try {
            STRICT_JSON.parseToJsonElement(text).jsonObject
        } catch (err: Exception) {
            out += Finding(
                rule = "SM068",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.physicalRelative,
                line = null,
                message = "Post effect definition is not valid JSON: ${err.message?.lineSequence()?.firstOrNull() ?: "syntax error"}.",
            )
            return
        }

        val passes = root["passes"] as? JsonArray
        if (passes != null && contract.postEffectSchema != "program-ref") {
            val hasLegacyProgram = passes.any { (it as? JsonObject)?.containsKey("program") == true }
            if (hasLegacyProgram) {
                out += Finding(
                    rule = "SM060",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "A pass with \"program\" is the 1.21.2-1.21.4 shape. On ${contract.id} a pass names " +
                        "\"vertex_shader\" and \"fragment_shader\" directly.",
                )
            }
        }
        if (contract.postUniformsAreBlocks && passes != null) {
            val hasListUniforms = passes.any { ((it as? JsonObject)?.get("uniforms")) is JsonArray }
            if (hasListUniforms) {
                out += Finding(
                    rule = "SM061",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "\"uniforms\" is a list, which is the 1.21.5 shape. On ${contract.id} it is a map " +
                        "from uniform block name to a list of {name, type, value}.",
                )
            }
        }

        if (contract.postEffectSchema == "program-ref" || contract.postEffectSchema == "legacy-shaders-post") return
        if (passes == null) return

        val declaredTargets = (root["targets"] as? JsonObject)?.keys ?: emptySet()
        val externalTargets = ((root["external_targets"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.toSet() ?: emptySet())
        val writtenTargets = mutableSetOf<String>()

        passes.forEachIndexed { passIndex, elem ->
            val pass = elem as? JsonObject ?: return@forEachIndexed
            val passLabel = "Pass #${passIndex + 1}"
            val vshId = (pass["vertex_shader"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val fshId = (pass["fragment_shader"] as? JsonPrimitive)?.takeIf { it.isString }?.content

            val vshLogical = vshId?.let { checkPostPassVertexShader(file, it, effectivePaths, out) }
            val fshLogical = fshId?.let { checkPostPassFragmentShader(file, it, effectivePaths, out) }

            val inputsArray = pass["inputs"] as? JsonArray
            val boundSamplerNames = mutableListOf<String>()
            inputsArray?.forEach { inputElem ->
                val inputObj = inputElem as? JsonObject ?: return@forEach
                val samplerName = (inputObj["sampler_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (samplerName != null) boundSamplerNames += samplerName
                val inputTarget = (inputObj["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (inputTarget != null && !isExternalTarget(inputTarget, externalTargets)) {
                    if (inputTarget !in declaredTargets) {
                        out += Finding(
                            rule = "SM065",
                            severity = Severity.ERROR,
                            version = contract.id,
                            file = file.physicalRelative,
                            line = null,
                            message = "$passLabel reads undeclared internal target '$inputTarget'. Declare it under \"targets\" or use a namespaced target such as \"minecraft:main\".",
                        )
                    } else if (inputTarget !in writtenTargets) {
                        out += Finding(
                            rule = "SM065",
                            severity = Severity.WARNING,
                            version = contract.id,
                            file = file.physicalRelative,
                            line = null,
                            message = "$passLabel reads internal target '$inputTarget' before any earlier pass writes to it.",
                        )
                    }
                }
            }

            val outputTarget = (pass["output"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (outputTarget != null) {
                if (!isExternalTarget(outputTarget, externalTargets)) {
                    if (outputTarget !in declaredTargets) {
                        out += Finding(
                            rule = "SM065",
                            severity = Severity.ERROR,
                            version = contract.id,
                            file = file.physicalRelative,
                            line = null,
                            message = "$passLabel writes to undeclared internal target '$outputTarget'. Declare it under \"targets\" or use a namespaced target such as \"minecraft:main\".",
                        )
                    } else {
                        writtenTargets += outputTarget
                    }
                }
            }

            val fshContract = fshLogical?.takeIf { it in glslByPath }?.let { collectTransitiveGlsl(it, glslByPath) }
            val vshContract = vshLogical?.takeIf { it in glslByPath }?.let { collectTransitiveGlsl(it, glslByPath) }
            val vshIsStandardQuad = vshId in STANDARD_SCREENQUAD_SHADERS && (vshLogical == null || vshLogical !in glslByPath)

            if (fshContract != null) {
                val boundUniformNames = boundSamplerNames.flatMap { listOf("${it}Sampler", it) }.toSet()
                fshContract.samplers.forEach { declaredSampler ->
                    if (declaredSampler !in boundUniformNames) {
                        val expectedInput = declaredSampler.removeSuffix("Sampler")
                        out += Finding(
                            rule = "SM066",
                            severity = Severity.ERROR,
                            version = contract.id,
                            file = file.physicalRelative,
                            line = null,
                            message = "$passLabel fragment_shader '$fshId' declares 'uniform sampler $declaredSampler', " +
                                "but pass inputs do not bind sampler_name '$expectedInput'.",
                        )
                    }
                }
                if (vshContract != null || vshIsStandardQuad) {
                    val allShaderSamplers = fshContract.samplers + (vshContract?.samplers ?: emptySet())
                    boundSamplerNames.forEach { bound ->
                        if ("${bound}Sampler" !in allShaderSamplers && bound !in allShaderSamplers) {
                            out += Finding(
                                rule = "SM066",
                                severity = Severity.WARNING,
                                version = contract.id,
                                file = file.physicalRelative,
                                line = null,
                                message = "$passLabel binds input sampler_name '$bound' (${bound}Sampler), which is not declared in '$fshId'.",
                            )
                        }
                    }
                }
            }

            if (contract.postUniformsAreBlocks) {
                val uniformsObj = pass["uniforms"] as? JsonObject
                if (uniformsObj != null) {
                    val knownBlocks = buildMap {
                        putAll(contract.uniformBlocks)
                        vshContract?.blocks?.let { putAll(it) }
                        fshContract?.blocks?.let { putAll(it) }
                    }
                    uniformsObj.forEach { (blockName, membersElem) ->
                        val membersArray = membersElem as? JsonArray ?: return@forEach
                        val jsonMembers = membersArray.mapNotNull { item ->
                            val mObj = item as? JsonObject ?: return@mapNotNull null
                            val mName = (mObj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                            val mType = (mObj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                            BlockMember(mType, mName)
                        }
                        val shaderMembers = knownBlocks[blockName]
                        if (shaderMembers != null) {
                            if (jsonMembers != shaderMembers) {
                                val given = jsonMembers.joinToString(", ") { "${it.type} ${it.name}" }
                                val expected = shaderMembers.joinToString(", ") { "${it.type} ${it.name}" }
                                out += Finding(
                                    rule = "SM067",
                                    severity = Severity.ERROR,
                                    version = contract.id,
                                    file = file.physicalRelative,
                                    line = null,
                                    message = "$passLabel uniform block '$blockName' does not match GLSL layout(std140) declaration. " +
                                        "Pass JSON declares [$given]; shader expects [$expected].",
                                )
                            }
                        } else if (fshContract != null && (vshContract != null || vshIsStandardQuad)) {
                            out += Finding(
                                rule = "SM067",
                                severity = Severity.WARNING,
                                version = contract.id,
                                file = file.physicalRelative,
                                line = null,
                                message = "$passLabel supplies uniform block '$blockName', which is not declared in '$fshId'.",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPostPassVertexShader(
        file: PackFile,
        declared: String,
        effectivePaths: Set<String>,
        out: MutableList<Finding>,
    ): String? {
        if (!declared.contains(':')) return null
        val ns = declared.substringBefore(':')
        val relPath = declared.substringAfter(':')
        val logical = "assets/$ns/shaders/$relPath.vsh"
        if (ns == "minecraft") {
            val inVanilla = declared == contract.postPassVertexShader ||
                (relPath.startsWith("core/") && "${relPath.removePrefix("core/")}.vsh" in contract.coreProgramNames) ||
                (relPath.startsWith("post/") && "${relPath.removePrefix("post/")}.vsh" in contract.postProgramNames)
            if (!inVanilla && logical !in effectivePaths) {
                val fix = if (declared in STANDARD_SCREENQUAD_SHADERS && declared != contract.postPassVertexShader) {
                    Fix.RewriteFile { text ->
                        text.replace(
                            Regex("(\"vertex_shader\"\\s*:\\s*\")${Regex.escape(declared)}(\")"),
                            "$1${contract.postPassVertexShader}$2",
                        )
                    }
                } else {
                    null
                }
                out += Finding(
                    rule = "SM062",
                    severity = Severity.WARNING,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "Pass vertex_shader '$declared'; vanilla ${contract.id} post passes use " +
                        "'${contract.postPassVertexShader}'. If '$declared' is not in the pack, the pass fails.",
                    fix = fix,
                )
            }
        } else if (logical !in effectivePaths) {
            out += Finding(
                rule = "SM064",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.physicalRelative,
                line = null,
                message = "Pass references vertex_shader '$declared', but '$logical' is missing from the pack.",
            )
        }
        return logical
    }

    private fun checkPostPassFragmentShader(
        file: PackFile,
        declared: String,
        effectivePaths: Set<String>,
        out: MutableList<Finding>,
    ): String? {
        if (!declared.contains(':')) return null
        val ns = declared.substringBefore(':')
        val relPath = declared.substringAfter(':')
        val logical = "assets/$ns/shaders/$relPath.fsh"
        if (ns == "minecraft") {
            val inVanilla = (relPath.startsWith("core/") && "${relPath.removePrefix("core/")}.fsh" in contract.coreProgramNames) ||
                (relPath.startsWith("post/") && "${relPath.removePrefix("post/")}.fsh" in contract.postProgramNames)
            if (!inVanilla && logical !in effectivePaths) {
                out += Finding(
                    rule = "SM064",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "Pass references fragment_shader '$declared', which does not exist in vanilla ${contract.id} or '$logical'.",
                )
            }
        } else if (logical !in effectivePaths) {
            out += Finding(
                rule = "SM064",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.physicalRelative,
                line = null,
                message = "Pass references fragment_shader '$declared', but '$logical' is missing from the pack.",
            )
        }
        return logical
    }

    private data class TransitiveGlslContract(
        val samplers: Set<String>,
        val blocks: Map<String, List<BlockMember>>,
    )

    private fun collectTransitiveGlsl(
        rootLogicalPath: String,
        glslByPath: Map<String, GlslSource>,
    ): TransitiveGlslContract {
        val samplers = linkedSetOf<String>()
        val blocks = linkedMapOf<String, List<BlockMember>>()
        val visited = mutableSetOf<String>()

        fun visit(path: String) {
            if (!visited.add(path)) return
            val src = glslByPath[path] ?: return
            samplers += src.samplers
            src.blocks.forEach { blocks[it.name] = it.members }
            src.includes.forEach { inc ->
                resolveIncludePath(path, inc.target, glslByPath.keys)?.let { visit(it) }
            }
        }

        visit(rootLogicalPath)
        return TransitiveGlslContract(samplers, blocks)
    }

    private fun isExternalTarget(target: String, externalTargets: Set<String>): Boolean =
        target.contains(':') || target in externalTargets

    /** Validates `pack.mcmeta` format fields and overlay directories, returning the parsed metadata. */
    private fun checkPackMeta(pack: PackSource, file: PackFile, out: MutableList<Finding>): DeclaredRange? {
        val range = PackMetaReader.read(file.text) ?: run {
            out += Finding("SM070", Severity.ERROR, contract.id, file.physicalRelative, null, "pack.mcmeta is not readable JSON with a \"pack\" section.")
            return null
        }
        val min = range.effectiveMin
        val max = range.effectiveMax
        if (min == null || max == null) {
            out += Finding("SM071", Severity.ERROR, contract.id, file.physicalRelative, null, "pack.mcmeta declares no pack format at all.")
            return range
        }
        if (min > max) {
            out += Finding(
                rule = "SM075",
                severity = Severity.ERROR,
                version = contract.id,
                file = file.physicalRelative,
                line = null,
                message = "pack.mcmeta declares inverted format range $min..$max (min_format exceeds max_format).",
            )
        } else if (contract.packFormat < min || contract.packFormat > max) {
            out += Finding(
                rule = "SM072",
                severity = Severity.WARNING,
                version = contract.id,
                file = file.physicalRelative,
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
                file = file.physicalRelative,
                line = null,
                message = "Formats above 64 require min_format and max_format in pack.mcmeta; pack_format alone " +
                    "cannot claim ${contract.id}.",
            )
        }
        range.overlays.forEach { overlay ->
            if (!overlay.hasValidDirectoryName) {
                out += Finding(
                    rule = "SM075",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "pack.mcmeta overlay directory '${overlay.rawDirectory}' violates [a-zA-Z0-9_-]+ and will be rejected by the client.",
                )
            } else if (overlay.min > overlay.max) {
                out += Finding(
                    rule = "SM075",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "pack.mcmeta overlay '${overlay.directory}' declares inverted format range ${overlay.min}..${overlay.max}.",
                )
            } else if (!pack.hasOverlayDirectory(overlay.directory)) {
                out += Finding(
                    rule = "SM074",
                    severity = Severity.WARNING,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "pack.mcmeta declares overlay directory '${overlay.directory}', which contains no shader or metadata files in the pack.",
                )
            }
        }
        return range
    }

    private fun rewriteBlock(text: String, name: String, members: List<BlockMember>): String {
        val pattern = Regex("(layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+$name\\s*\\{)[^}]*(\\})")
        val body = members.joinToString("") { "\n    ${it.type} ${it.name};" } + "\n"
        return pattern.replace(text) { match -> match.groupValues[1] + body + match.groupValues[2] }
    }

    companion object {
        private val STRICT_JSON = Json { ignoreUnknownKeys = true }
        private val STANDARD_SCREENQUAD_SHADERS = setOf(
            "minecraft:core/screenquad",
            "minecraft:post/screenquad",
            "minecraft:post/blit",
        )
    }
}
