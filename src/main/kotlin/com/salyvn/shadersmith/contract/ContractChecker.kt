package com.salyvn.shadersmith.contract

import com.salyvn.shadersmith.layout.SsboValidator
import com.salyvn.shadersmith.pack.DeclaredSampler
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.GlslStruct
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.pack.SamplerValueKind
import com.salyvn.shadersmith.pipeline.PipelineCompiler
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Fix
import com.salyvn.shadersmith.report.Severity
import com.salyvn.shadersmith.vram.VramCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Checks one pack against one version's contract, resolving active `pack.mcmeta` overlays for the version's
 * pack format before checking shaders, include dependency graphs, std140/std430 buffer layouts, 26.3 core
 * shader pipeline states, and multi-pass post-effect texture format bindings.
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
                file.isShaderSource -> {
                    val transitive = collectTransitiveGlsl(file.relative, glslByPath)
                    checkSource(file, glslByPath.getValue(file.relative), effectivePaths, transitive, findings)
                }
                file.isPostEffectDefinition -> checkPostEffect(file, effectiveByPath, effectivePaths, glslByPath, findings)
            }
        }

        checkCorePipelinePairs(effective, glslByPath, findings)

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
        transitive: TransitiveGlslContract,
        out: MutableList<Finding>,
    ) {
        checkOverridesVanilla(file, out)
        checkVersionLine(file, source, out)
        checkDirectives(file, source, effectivePaths, out)
        checkBlocks(file, source, out)
        SsboValidator.checkFile(
            file = file,
            source = source,
            versionId = contract.id,
            defaultGlslVersion = contract.glslVersion,
            transitiveStructs = transitive.structs,
            transitiveExtensions = transitive.extensions,
            out = out,
        )
        checkInterfaceLayout(file, source, out)
        PipelineCompiler.checkSingleStage(
            file = file,
            source = source,
            contract = contract,
            transitiveIncludes = transitive.includeTargets,
            out = out,
        )
        checkVertexId(file, source, out)
    }

    private fun checkCorePipelinePairs(
        effective: List<PackFile>,
        glslByPath: Map<String, GlslSource>,
        out: MutableList<Finding>,
    ) {
        val coreShaders = effective.filter { it.shaderDirectory == "core" && it.isShaderSource }
        val byProgram = coreShaders.groupBy { "${it.namespace ?: "minecraft"}:${it.fileName.substringBeforeLast('.')}" }
        byProgram.values.forEach { stageFiles ->
            val vsh = stageFiles.firstOrNull { it.fileName.endsWith(".vsh") } ?: return@forEach
            val fsh = stageFiles.firstOrNull { it.fileName.endsWith(".fsh") } ?: return@forEach
            PipelineCompiler.checkStagePair(
                vshFile = vsh,
                vshSource = glslByPath.getValue(vsh.relative),
                fshFile = fsh,
                fshSource = glslByPath.getValue(fsh.relative),
                contract = contract,
                out = out,
            )
        }
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

    private enum class TargetFormatFamily { FLOAT, INT, UINT, DEPTH }

    private data class TargetSpec(
        val name: String,
        val persistent: Boolean,
        val formatName: String,
        val family: TargetFormatFamily,
    )

    /**
     * Validates post-effect JSON syntax, schema era, vertex/fragment shader existence, internal/external
     * render target graph wiring, multi-pass texture format bindings, ping-pong feedback hazards, sampler
     * bindings, and std140 uniform block contracts.
     */
    private fun checkPostEffect(
        file: PackFile,
        effectiveByPath: Map<String, PackFile>,
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

        val targetSpecs = parseAndValidateTargets(file, root["targets"] as? JsonObject, out)
        val declaredTargets = targetSpecs.keys
        val externalTargets = ((root["external_targets"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.toSet() ?: emptySet())
        val writtenTargets = mutableSetOf<String>()
        val readTargets = mutableSetOf<String>()
        val checkedStagePairs = mutableSetOf<Pair<String, String>>()

        passes.forEachIndexed { passIndex, elem ->
            val pass = elem as? JsonObject ?: return@forEachIndexed
            val passLabel = "Pass #${passIndex + 1}"
            val vshId = (pass["vertex_shader"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val fshId = (pass["fragment_shader"] as? JsonPrimitive)?.takeIf { it.isString }?.content

            val vshLogical = vshId?.let { checkPostPassVertexShader(file, it, effectivePaths, out) }
            val fshLogical = fshId?.let { checkPostPassFragmentShader(file, it, effectivePaths, out) }

            if (vshLogical != null && fshLogical != null && vshLogical in glslByPath && fshLogical in glslByPath) {
                val pairKey = vshLogical to fshLogical
                if (checkedStagePairs.add(pairKey)) {
                    PipelineCompiler.checkStagePair(
                        vshFile = effectiveByPath.getValue(vshLogical),
                        vshSource = glslByPath.getValue(vshLogical),
                        fshFile = effectiveByPath.getValue(fshLogical),
                        fshSource = glslByPath.getValue(fshLogical),
                        contract = contract,
                        out = out,
                    )
                }
            }

            val fshContract = fshLogical?.takeIf { it in glslByPath }?.let { collectTransitiveGlsl(it, glslByPath) }
            val vshContract = vshLogical?.takeIf { it in glslByPath }?.let { collectTransitiveGlsl(it, glslByPath) }
            val vshIsStandardQuad = vshId in STANDARD_SCREENQUAD_SHADERS && (vshLogical == null || vshLogical !in glslByPath)

            val outputTarget = (pass["output"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val inputsArray = pass["inputs"] as? JsonArray
            val boundSamplerNames = mutableListOf<String>()

            inputsArray?.forEach { inputElem ->
                val inputObj = inputElem as? JsonObject ?: return@forEach
                val samplerName = (inputObj["sampler_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (samplerName != null) boundSamplerNames += samplerName

                val inputTarget = (inputObj["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val texLocation = (inputObj["location"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val useDepth = (inputObj["use_depth_buffer"] as? JsonPrimitive)?.booleanOrNull ?: false
                val bilinear = (inputObj["bilinear"] as? JsonPrimitive)?.booleanOrNull ?: false

                if (texLocation != null && inputTarget == null) {
                    val w = (inputObj["width"] as? JsonPrimitive)?.intOrNull
                    val h = (inputObj["height"] as? JsonPrimitive)?.intOrNull
                    if (w == null || h == null || w <= 0 || h <= 0) {
                        out += Finding(
                            rule = "SM076",
                            severity = Severity.ERROR,
                            version = contract.id,
                            file = file.physicalRelative,
                            line = null,
                            message = "$passLabel texture input '$texLocation' (sampler '${samplerName ?: "?"}') must declare positive integer 'width' and 'height'.",
                        )
                    }
                }

                if (inputTarget != null) {
                    if (!isExternalTarget(inputTarget, externalTargets)) {
                        readTargets += inputTarget
                        val spec = targetSpecs[inputTarget]
                        if (spec == null) {
                            out += Finding(
                                rule = "SM065",
                                severity = Severity.ERROR,
                                version = contract.id,
                                file = file.physicalRelative,
                                line = null,
                                message = "$passLabel reads undeclared internal target '$inputTarget'. Declare it under \"targets\" or use a namespaced target such as \"minecraft:main\".",
                            )
                        } else if (inputTarget !in writtenTargets && !spec.persistent) {
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

                    // Same-pass read/write feedback hazard (ping-pong violation, SM069)
                    if (outputTarget != null && inputTarget == outputTarget && !useDepth) {
                        out += Finding(
                            rule = "SM069",
                            severity = Severity.ERROR,
                            version = contract.id,
                            file = file.physicalRelative,
                            line = null,
                            message = "$passLabel binds target '$inputTarget' as both an input sampler ('${samplerName ?: "?"}') and the pass 'output' without a ping-pong swap target, creating an OpenGL read/write feedback loop.",
                        )
                    }

                    // Texture format & depth/bilinear binding checks against GLSL sampler declaration (SM069)
                    val targetSpec = targetSpecs[inputTarget]
                    val effectiveFamily = when {
                        useDepth -> TargetFormatFamily.DEPTH
                        targetSpec != null -> targetSpec.family
                        else -> TargetFormatFamily.FLOAT
                    }
                    val formatLabel = if (useDepth) "depth_buffer" else (targetSpec?.formatName ?: "RGBA8")

                    if (bilinear && (effectiveFamily == TargetFormatFamily.INT || effectiveFamily == TargetFormatFamily.UINT)) {
                        out += Finding(
                            rule = "SM069",
                            severity = Severity.ERROR,
                            version = contract.id,
                            file = file.physicalRelative,
                            line = null,
                            message = "$passLabel enables 'bilinear: true' on integer render target '$inputTarget' ($formatLabel); OpenGL forbids GL_LINEAR filtering on integer texture formats.",
                        )
                    }

                    if (samplerName != null && fshContract != null) {
                        val matchedSampler = fshContract.declaredSamplers["${samplerName}Sampler"]
                            ?: fshContract.declaredSamplers[samplerName]
                            ?: vshContract?.declaredSamplers?.get("${samplerName}Sampler")
                            ?: vshContract?.declaredSamplers?.get(samplerName)

                        if (matchedSampler != null) {
                            checkSamplerFormatCompatibility(
                                file = file,
                                passLabel = passLabel,
                                inputTarget = inputTarget,
                                formatLabel = formatLabel,
                                family = effectiveFamily,
                                bilinear = bilinear,
                                sampler = matchedSampler,
                                out = out,
                            )
                        } else if (bilinear && effectiveFamily == TargetFormatFamily.DEPTH) {
                            out += Finding(
                                rule = "SM069",
                                severity = Severity.WARNING,
                                version = contract.id,
                                file = file.physicalRelative,
                                line = null,
                                message = "$passLabel samples depth attachment '$inputTarget' with 'bilinear: true'; linear filtering of non-linear hardware depth values distorts reconstructed positions.",
                            )
                        }
                    } else if (bilinear && effectiveFamily == TargetFormatFamily.DEPTH) {
                        out += Finding(
                            rule = "SM069",
                            severity = Severity.WARNING,
                            version = contract.id,
                            file = file.physicalRelative,
                            line = null,
                            message = "$passLabel samples depth attachment '$inputTarget' with 'bilinear: true'; linear filtering of non-linear hardware depth values distorts reconstructed positions.",
                        )
                    }
                }
            }

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

        // Flag declared internal render targets never read by any pass (only when passes exist)
        if (passes.isNotEmpty()) {
            declaredTargets.forEach { targetName ->
                if (targetName !in readTargets && (targetName in writtenTargets || passes.size > 0)) {
                    // Do not warn if no pass has any inputs/outputs at all (empty stub)
                    val anyPassHasIo = passes.any { (it as? JsonObject)?.let { o -> "inputs" in o || "output" in o } == true }
                    if (anyPassHasIo) {
                        out += Finding(
                            rule = "SM076",
                            severity = Severity.WARNING,
                            version = contract.id,
                            file = file.physicalRelative,
                            line = null,
                            message = "Internal target '$targetName' is declared under \"targets\" but never sampled by any pass.",
                        )
                    }
                }
            }
        }

        VramCalculator.analyzePostEffect(
            file = file,
            versionId = contract.id,
            out = out,
        )
    }

    private fun parseAndValidateTargets(
        file: PackFile,
        targetsObj: JsonObject?,
        out: MutableList<Finding>,
    ): Map<String, TargetSpec> {
        if (targetsObj == null) return emptyMap()
        val result = linkedMapOf<String, TargetSpec>()
        for ((name, elem) in targetsObj) {
            val cfg = elem as? JsonObject
            if (cfg == null) {
                out += Finding(
                    rule = "SM076",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "Internal target '$name' must be a JSON object.",
                )
                continue
            }
            val hasWidth = "width" in cfg
            val hasHeight = "height" in cfg
            val width = (cfg["width"] as? JsonPrimitive)?.intOrNull
            val height = (cfg["height"] as? JsonPrimitive)?.intOrNull
            if (hasWidth != hasHeight) {
                out += Finding(
                    rule = "SM076",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "Internal target '$name' defines ${if (hasWidth) "'width' without 'height'" else "'height' without 'width'"}; Minecraft's PostChainConfig codec requires both dimensions or neither.",
                )
            } else if (hasWidth && (width == null || height == null || width !in 1..16384 || height !in 1..16384)) {
                out += Finding(
                    rule = "SM076",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "Internal target '$name' has invalid dimensions (${cfg["width"]} x ${cfg["height"]}); expected integers in 1..16384.",
                )
            }

            val persistentElem = cfg["persistent"]
            val persistent = if (persistentElem != null) {
                val b = (persistentElem as? JsonPrimitive)?.booleanOrNull
                if (b == null) {
                    out += Finding(
                        rule = "SM076",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = null,
                        message = "Internal target '$name' field 'persistent' must be a boolean.",
                    )
                    false
                } else {
                    b
                }
            } else {
                false
            }

            val clearElem = cfg["clear_color"]
            if (clearElem != null) {
                val validInt = (clearElem as? JsonPrimitive)?.intOrNull != null
                val validArr = (clearElem as? JsonArray)?.let { arr ->
                    arr.size == 4 && arr.all { (it as? JsonPrimitive)?.doubleOrNull != null }
                } == true
                if (!validInt && !validArr) {
                    out += Finding(
                        rule = "SM076",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = null,
                        message = "Internal target '$name' has invalid 'clear_color'; expected a packed ARGB integer or 4-element [r, g, b, a] float array.",
                    )
                }
            }

            val rawFormat = ((cfg["format"] ?: cfg["pixel_format"]) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val normFormat = rawFormat?.trim()?.uppercase() ?: "RGBA8"
            val family = classifyTargetFormat(normFormat)
            if (family == null) {
                out += Finding(
                    rule = "SM076",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "Internal target '$name' declares unknown texture format '$rawFormat'. Known formats: ${KNOWN_TARGET_FORMATS.keys.joinToString(", ")}.",
                )
                result[name] = TargetSpec(name, persistent, normFormat, TargetFormatFamily.FLOAT)
            } else {
                result[name] = TargetSpec(name, persistent, normFormat, family)
                val isOitTarget = name.startsWith("oit_") || name.contains("transmittance") || name.contains("accumulate") || name.contains("moments")
                val isOitFloatPrecision = normFormat in setOf("RGBA16F", "RGBA32F", "RG16F", "R16F", "R32F", "RG32F", "R11F_G11F_B10F")
                if (isOitTarget && !isOitFloatPrecision) {
                    out += Finding(
                        rule = "SM056",
                        severity = Severity.WARNING,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = null,
                        message = "Wavelet OIT target '$name' uses non-floating-point or 8-bit format '$normFormat'. Transmittance estimation and moment accumulation require 16-bit or 32-bit floating-point textures (e.g. 'rgba16f', 'rgba32f'); 8-bit integer formats cause severe transmittance quantization banding and moment collapse.",
                    )
                }
            }
        }
        return result
    }

    private fun checkSamplerFormatCompatibility(
        file: PackFile,
        passLabel: String,
        inputTarget: String,
        formatLabel: String,
        family: TargetFormatFamily,
        bilinear: Boolean,
        sampler: DeclaredSampler,
        out: MutableList<Finding>,
    ) {
        when (sampler.valueKind) {
            SamplerValueKind.INT -> if (family != TargetFormatFamily.INT) {
                out += Finding(
                    rule = "SM069",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "$passLabel binds $formatLabel target '$inputTarget' to signed integer '${sampler.type} ${sampler.name}', which requires a signed integer render target format (e.g. RGBA32I, R32I).",
                )
            }
            SamplerValueKind.UINT -> if (family != TargetFormatFamily.UINT) {
                out += Finding(
                    rule = "SM069",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "$passLabel binds $formatLabel target '$inputTarget' to unsigned integer '${sampler.type} ${sampler.name}', which requires an unsigned integer render target format (e.g. RGBA32UI, R32UI).",
                )
            }
            SamplerValueKind.SHADOW -> if (family != TargetFormatFamily.DEPTH) {
                out += Finding(
                    rule = "SM069",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "$passLabel binds color target '$inputTarget' ($formatLabel) to depth-comparison '${sampler.type} ${sampler.name}' without 'use_depth_buffer: true' or a depth format.",
                )
            }
            SamplerValueKind.FLOAT -> {
                if (family == TargetFormatFamily.INT || family == TargetFormatFamily.UINT) {
                    val expectedSampler = if (family == TargetFormatFamily.INT) "isampler2D" else "usampler2D"
                    out += Finding(
                        rule = "SM069",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = null,
                        message = "$passLabel binds integer render target '$inputTarget' ($formatLabel) to floating-point '${sampler.type} ${sampler.name}'; declare '$expectedSampler ${sampler.name}' or use a normalized/float target format.",
                    )
                } else if (family == TargetFormatFamily.DEPTH && bilinear) {
                    out += Finding(
                        rule = "SM069",
                        severity = Severity.WARNING,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = null,
                        message = "$passLabel samples depth attachment '$inputTarget' via '${sampler.type} ${sampler.name}' with 'bilinear: true'; linear filtering of non-linear hardware depth values distorts reconstructed positions.",
                    )
                }
            }
        }
    }

    private fun classifyTargetFormat(format: String): TargetFormatFamily? = KNOWN_TARGET_FORMATS[format]

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
        val declaredSamplers: Map<String, DeclaredSampler>,
        val blocks: Map<String, List<BlockMember>>,
        val structs: Map<String, GlslStruct>,
        val extensions: Set<String>,
        val includeTargets: Set<String>,
    )

    private fun collectTransitiveGlsl(
        rootLogicalPath: String,
        glslByPath: Map<String, GlslSource>,
    ): TransitiveGlslContract {
        val samplers = linkedSetOf<String>()
        val declaredSamplers = linkedMapOf<String, DeclaredSampler>()
        val blocks = linkedMapOf<String, List<BlockMember>>()
        val structs = linkedMapOf<String, GlslStruct>()
        val extensions = linkedSetOf<String>()
        val includeTargets = linkedSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(path: String) {
            if (!visited.add(path)) return
            val src = glslByPath[path] ?: return
            samplers += src.samplers
            src.declaredSamplers.forEach { declaredSamplers[it.name] = it }
            src.blocks.forEach { blocks[it.name] = it.members }
            src.structs.forEach { structs[it.name] = it }
            src.extensions.filter { it.behavior in setOf("require", "enable", "warn") }.forEach { extensions += it.name }
            src.includes.forEach { inc ->
                includeTargets += inc.target
                resolveIncludePath(path, inc.target, glslByPath.keys)?.let { resolved ->
                    includeTargets += resolved
                    visit(resolved)
                }
            }
        }

        visit(rootLogicalPath)
        return TransitiveGlslContract(samplers, declaredSamplers, blocks, structs, extensions, includeTargets)
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
        private val KNOWN_TARGET_FORMATS: Map<String, TargetFormatFamily> = mapOf(
            "RGBA8" to TargetFormatFamily.FLOAT,
            "RGB8" to TargetFormatFamily.FLOAT,
            "SRGB8_ALPHA8" to TargetFormatFamily.FLOAT,
            "R8" to TargetFormatFamily.FLOAT,
            "RG8" to TargetFormatFamily.FLOAT,
            "R16" to TargetFormatFamily.FLOAT,
            "RG16" to TargetFormatFamily.FLOAT,
            "RGBA16" to TargetFormatFamily.FLOAT,
            "R16F" to TargetFormatFamily.FLOAT,
            "RG16F" to TargetFormatFamily.FLOAT,
            "RGBA16F" to TargetFormatFamily.FLOAT,
            "R32F" to TargetFormatFamily.FLOAT,
            "RG32F" to TargetFormatFamily.FLOAT,
            "RGBA32F" to TargetFormatFamily.FLOAT,
            "R11F_G11F_B10F" to TargetFormatFamily.FLOAT,
            "RGB10_A2" to TargetFormatFamily.FLOAT,
            "R8I" to TargetFormatFamily.INT,
            "R16I" to TargetFormatFamily.INT,
            "R32I" to TargetFormatFamily.INT,
            "RG8I" to TargetFormatFamily.INT,
            "RG16I" to TargetFormatFamily.INT,
            "RG32I" to TargetFormatFamily.INT,
            "RGBA8I" to TargetFormatFamily.INT,
            "RGBA16I" to TargetFormatFamily.INT,
            "RGBA32I" to TargetFormatFamily.INT,
            "R8UI" to TargetFormatFamily.UINT,
            "R16UI" to TargetFormatFamily.UINT,
            "R32UI" to TargetFormatFamily.UINT,
            "RG8UI" to TargetFormatFamily.UINT,
            "RG16UI" to TargetFormatFamily.UINT,
            "RG32UI" to TargetFormatFamily.UINT,
            "RGBA8UI" to TargetFormatFamily.UINT,
            "RGBA16UI" to TargetFormatFamily.UINT,
            "RGBA32UI" to TargetFormatFamily.UINT,
            "DEPTH_COMPONENT" to TargetFormatFamily.DEPTH,
            "DEPTH_COMPONENT16" to TargetFormatFamily.DEPTH,
            "DEPTH_COMPONENT24" to TargetFormatFamily.DEPTH,
            "DEPTH_COMPONENT32F" to TargetFormatFamily.DEPTH,
            "DEPTH24_STENCIL8" to TargetFormatFamily.DEPTH,
            "DEPTH32F" to TargetFormatFamily.DEPTH,
            "DEPTH32F_STENCIL8" to TargetFormatFamily.DEPTH,
        )
    }
}
