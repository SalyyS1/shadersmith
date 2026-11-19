package com.salyvn.shadersmith.pressure

import com.salyvn.shadersmith.pack.BufferStorageKind
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.GlslStruct
import com.salyvn.shadersmith.pack.IoDirection
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackingStandard
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Severity
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max

@Serializable
data class LiveVariableSummary(
    val name: String,
    val type: String,
    val line: Int,
    val scalarComponents: Int,
    val vec4Registers: Int,
    val firstStmt: Int,
    val lastStmt: Int,
    val readCount: Int,
    val dynamicallyIndexed: Boolean,
)

@Serializable
data class FunctionPressureReport(
    val functionName: String,
    val line: Int,
    val statementCount: Int,
    val loopCount: Int,
    val peakVec4Registers: Int,
    val peakScalarComponents: Int,
    val peakLine: Int,
    val estimatedOccupancyPct: Int,
    val liveAtPeak: List<String>,
    val variables: List<LiveVariableSummary>,
)

@Serializable
data class PressureFindingDto(
    val rule: String,
    val severity: String,
    val version: String,
    val file: String,
    val line: Int?,
    val message: String,
)

@Serializable
data class FilePressureReport(
    val file: String,
    val stage: String,
    val maxVec4Budget: Int,
    val peakVec4Registers: Int,
    val peakScalarComponents: Int,
    val estimatedOccupancyPct: Int,
    val deadSymbols: List<String>,
    val functions: List<FunctionPressureReport>,
    val findings: List<PressureFindingDto>,
)

/**
 * Static GLSL dead-uniform, dead-sampler, non-uniform derivative, and register live-range pressure analyzer
 * (`SM059`, `SM077`, `SM078`, `SM079`).
 *
 * Models per-function statement sequences, loop back-edges (`for`, `while`, `do-while`), non-uniform branch
 * taint propagation, and `[firstDef..lastUse]` live intervals to estimate peak GPU vector register (`vec4`)
 * and scalar VGPR pressure alongside scratch-spill hazards.
 */
object RegisterPressureAnalyzer {

    const val DEFAULT_MAX_VEC4_TEMPS: Int = 32

    private val BUILTIN_ENGINE_UNIFORM_BLOCKS: Set<String> = setOf(
        "SamplerInfo", "BlitConfig", "BlurConfig", "ColorConfig", "SpiderConfig", "InvertConfig",
    )

    private val SCALAR_COMPONENTS_BY_TYPE: Map<String, Int> = mapOf(
        "bool" to 1, "int" to 1, "uint" to 1, "float" to 1,
        "double" to 2,
        "bvec2" to 2, "ivec2" to 2, "uvec2" to 2, "vec2" to 2,
        "bvec3" to 3, "ivec3" to 3, "uvec3" to 3, "vec3" to 3,
        "bvec4" to 4, "ivec4" to 4, "uvec4" to 4, "vec4" to 4,
        "dvec2" to 4, "dvec3" to 6, "dvec4" to 8,
        "mat2" to 4, "mat2x2" to 4, "mat2x3" to 6, "mat2x4" to 8,
        "mat3" to 9, "mat3x2" to 6, "mat3x3" to 9, "mat3x4" to 12,
        "mat4" to 16, "mat4x2" to 8, "mat4x3" to 12, "mat4x4" to 16,
        "dmat2" to 8, "dmat3" to 18, "dmat4" to 32,
    )

    private val VEC4_SLOTS_BY_TYPE: Map<String, Int> = mapOf(
        "bool" to 1, "int" to 1, "uint" to 1, "float" to 1, "double" to 1,
        "bvec2" to 1, "ivec2" to 1, "uvec2" to 1, "vec2" to 1, "dvec2" to 1,
        "bvec3" to 1, "ivec3" to 1, "uvec3" to 1, "vec3" to 1, "dvec3" to 2,
        "bvec4" to 1, "ivec4" to 1, "uvec4" to 1, "vec4" to 1, "dvec4" to 2,
        "mat2" to 2, "mat2x2" to 2, "mat2x3" to 2, "mat2x4" to 2,
        "mat3" to 3, "mat3x2" to 3, "mat3x3" to 3, "mat3x4" to 3,
        "mat4" to 4, "mat4x2" to 4, "mat4x3" to 4, "mat4x4" to 4,
        "dmat2" to 2, "dmat3" to 6, "dmat4" to 8,
    )

    private val NON_UNIFORM_BUILTINS: Set<String> = setOf(
        "gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_SampleID", "gl_SamplePosition",
        "gl_VertexID", "gl_VertexIndex", "gl_InstanceID", "gl_InstanceIndex",
        "gl_GlobalInvocationID", "gl_LocalInvocationID", "gl_LocalInvocationIndex", "gl_PrimitiveID",
    )

    private val IMPLICIT_DERIVATIVE_FUNCS: Set<String> = setOf(
        "texture", "textureProj", "textureOffset", "textureProjOffset",
        "dFdx", "dFdy", "dFdxFine", "dFdyFine", "dFdxCoarse", "dFdyCoarse",
        "fwidth", "fwidthFine", "fwidthCoarse",
    )

    private val TAINTING_FETCH_FUNCS: Set<String> = setOf(
        "texture", "textureLod", "textureGrad", "textureProj", "textureOffset",
        "texelFetch", "texelFetchOffset", "textureGather", "imageLoad",
    )

    private val GLSL_Ident = Regex("^[A-Za-z_]\\w*$")
    private val TOKEN_IDENT = Regex("\\b([A-Za-z_]\\w*)\\b")
    private val FUNC_CALL = Regex("\\b([A-Za-z_]\\w*)\\s*\\(")
    private val PRECISION_OR_CONST = setOf("const", "highp", "mediump", "lowp", "in", "out", "inout")

    fun checkFile(
        file: PackFile,
        source: GlslSource,
        versionId: String,
        vanillaUniformBlocks: Set<String> = emptySet(),
        transitiveStructs: Map<String, GlslStruct> = emptyMap(),
        transitiveExecutableCode: String = "",
        maxVec4Temps: Int = DEFAULT_MAX_VEC4_TEMPS,
        out: MutableList<Finding>,
    ): FilePressureReport {
        val localFindings = mutableListOf<Finding>()
        val allStructs = buildMap {
            putAll(transitiveStructs)
            source.structs.forEach { put(it.name, it) }
        }

        val stage = when {
            file.isGlslInclude -> "include"
            file.fileName.endsWith(".vsh") -> "vertex"
            file.fileName.endsWith(".fsh") -> "fragment"
            file.fileName.endsWith(".csh") -> "compute"
            else -> "shader"
        }

        val deadSymbols = mutableListOf<String>()
        if (!file.isGlslInclude) {
            checkDeadUniformsAndSamplers(
                file = file,
                source = source,
                versionId = versionId,
                vanillaUniformBlocks = vanillaUniformBlocks,
                combinedExecCode = source.executableCode + "\n" + transitiveExecutableCode,
                deadSymbols = deadSymbols,
                out = localFindings,
            )
        }

        val baseNonUniforms = buildSet {
            addAll(NON_UNIFORM_BUILTINS)
            source.interfaceVariables.filter { it.direction == IoDirection.IN }.forEach { add(it.name) }
            source.unqualifiedInterfaceVariables.forEach { (decl, _) ->
                val tokens = decl.removeSuffix(";").trim().split(Regex("\\s+"))
                if ("in" in tokens && tokens.isNotEmpty()) add(tokens.last())
            }
            source.bufferBlocks.filter { it.storage == BufferStorageKind.BUFFER }.forEach { blk ->
                if (blk.instanceName != null) add(blk.instanceName)
                else blk.members.forEach { add(it.name) }
            }
            source.sharedVariables.forEach { add(it.name) }
        }

        val functions = extractFunctions(source.cleanText).map { fn ->
            analyzeFunction(
                file = file,
                cleanText = source.cleanText,
                fn = fn,
                isFragment = stage == "fragment",
                versionId = versionId,
                structs = allStructs,
                baseNonUniforms = baseNonUniforms,
                maxVec4Temps = maxVec4Temps,
                out = localFindings,
            )
        }

        out += localFindings

        val peakVec4 = functions.maxOfOrNull { it.peakVec4Registers } ?: 0
        val peakScalars = functions.maxOfOrNull { it.peakScalarComponents } ?: 0
        val occupancy = estimateOccupancyPct(peakVec4)

        return FilePressureReport(
            file = file.physicalRelative,
            stage = stage,
            maxVec4Budget = maxVec4Temps,
            peakVec4Registers = peakVec4,
            peakScalarComponents = peakScalars,
            estimatedOccupancyPct = occupancy,
            deadSymbols = deadSymbols,
            functions = functions,
            findings = localFindings.map {
                PressureFindingDto(
                    rule = it.rule,
                    severity = it.severity.label,
                    version = it.version,
                    file = it.file,
                    line = it.line,
                    message = it.message,
                )
            },
        )
    }

    private fun checkDeadUniformsAndSamplers(
        file: PackFile,
        source: GlslSource,
        versionId: String,
        vanillaUniformBlocks: Set<String>,
        combinedExecCode: String,
        deadSymbols: MutableList<String>,
        out: MutableList<Finding>,
    ) {
        val referencedIdents = TOKEN_IDENT.findAll(combinedExecCode).map { it.groupValues[1] }.toSet()

        for (sampler in source.declaredSamplers) {
            if (sampler.name !in referencedIdents) {
                deadSymbols += sampler.name
                out += Finding(
                    rule = "SM059",
                    severity = Severity.WARNING,
                    version = versionId,
                    file = file.physicalRelative,
                    line = sampler.line,
                    message = "Sampler uniform '${sampler.type} ${sampler.name}' is declared but never sampled in executable shader code, wasting a texture unit binding.",
                )
            }
        }

        for ((decl, line) in source.nakedUniforms) {
            val parts = decl.trim().split(Regex("\\s+"))
            val name = parts.lastOrNull() ?: continue
            if (name !in referencedIdents) {
                deadSymbols += name
                out += Finding(
                    rule = "SM059",
                    severity = Severity.WARNING,
                    version = versionId,
                    file = file.physicalRelative,
                    line = line,
                    message = "Uniform '$decl' is declared at file scope but never read in executable shader code.",
                )
            }
        }

        for (block in source.bufferBlocks) {
            if (block.storage != BufferStorageKind.UNIFORM) continue
            if (block.name in vanillaUniformBlocks || block.name in BUILTIN_ENGINE_UNIFORM_BLOCKS) continue
            if (block.members.isEmpty()) continue

            if (block.instanceName != null) {
                if (block.instanceName !in referencedIdents) {
                    deadSymbols += "${block.name}.${block.instanceName}"
                    out += Finding(
                        rule = "SM059",
                        severity = Severity.WARNING,
                        version = versionId,
                        file = file.physicalRelative,
                        line = block.line,
                        message = "Custom uniform block '${block.name}' (instance '${block.instanceName}') is never referenced in executable shader code.",
                    )
                }
                continue
            }

            val usedFlags = block.members.map { it.name in referencedIdents }
            if (usedFlags.none { it }) {
                deadSymbols += block.name
                out += Finding(
                    rule = "SM059",
                    severity = Severity.WARNING,
                    version = versionId,
                    file = file.physicalRelative,
                    line = block.line,
                    message = "Custom uniform block '${block.name}' declares ${block.members.size} member(s), none of which are referenced in executable shader code.",
                )
            } else if (block.packing == PackingStandard.STD140) {
                val lastUsedIdx = usedFlags.indexOfLast { it }
                for (idx in (lastUsedIdx + 1) until block.members.size) {
                    val member = block.members[idx]
                    deadSymbols += "${block.name}.${member.name}"
                    out += Finding(
                        rule = "SM059",
                        severity = Severity.WARNING,
                        version = versionId,
                        file = file.physicalRelative,
                        line = member.line,
                        message = "Trailing std140 uniform member '${block.name}.${member.name}' is never read and can be removed without shifting earlier member byte offsets.",
                    )
                }
            } else {
                block.members.forEachIndexed { idx, member ->
                    if (!usedFlags[idx]) {
                        deadSymbols += "${block.name}.${member.name}"
                        out += Finding(
                            rule = "SM059",
                            severity = Severity.WARNING,
                            version = versionId,
                            file = file.physicalRelative,
                            line = member.line,
                            message = "Uniform block member '${block.name}.${member.name}' is declared but never read in executable shader code.",
                        )
                    }
                }
            }
        }
    }

    private data class ParsedFunction(
        val name: String,
        val returnType: String,
        val paramsText: String,
        val headerLine: Int,
        val bodyText: String,
        val bodyOffset: Int,
    )

    private fun extractFunctions(cleanText: String): List<ParsedFunction> {
        val result = mutableListOf<ParsedFunction>()
        var i = 0
        val n = cleanText.length
        var headerStart = 0

        while (i < n) {
            if (cleanText[i] == '#' && isAtLineStart(cleanText, i)) {
                while (i < n && cleanText[i] != '\n') i++
                if (i < n) i++
                headerStart = i
                continue
            }
            val ch = cleanText[i]
            if (ch == ';') {
                i++
                headerStart = i
                continue
            }
            if (ch == '{') {
                val rawHeader = cleanText.substring(headerStart, i)
                val trimmedHeader = rawHeader.trim()
                val bodyStart = i + 1
                var depth = 1
                i++
                while (i < n && depth > 0) {
                    if (cleanText[i] == '{') depth++
                    else if (cleanText[i] == '}') depth--
                    i++
                }
                val bodyEnd = (i - 1).coerceAtLeast(bodyStart)

                if (trimmedHeader.endsWith(")")) {
                    val openParen = trimmedHeader.indexOf('(')
                    if (openParen > 0) {
                        val beforeParen = trimmedHeader.substring(0, openParen).trim()
                        val paramsText = trimmedHeader.substring(openParen + 1, trimmedHeader.length - 1).trim()
                        val headerTokens = beforeParen.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        if (headerTokens.size >= 2) {
                            val fnName = headerTokens.last()
                            val retType = headerTokens[headerTokens.size - 2]
                            if (GLSL_Ident.matches(fnName)) {
                                val firstNonWs = rawHeader.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                                val headerLine = lineAt(cleanText, headerStart + firstNonWs)
                                result += ParsedFunction(
                                    name = fnName,
                                    returnType = retType,
                                    paramsText = paramsText,
                                    headerLine = headerLine,
                                    bodyText = cleanText.substring(bodyStart, bodyEnd),
                                    bodyOffset = bodyStart,
                                )
                            }
                        }
                    }
                    headerStart = i
                    continue
                }

                val semi = cleanText.indexOf(';', i)
                if (semi >= 0) i = semi + 1
                headerStart = i
                continue
            }
            i++
        }
        return result
    }

    private class TrackedVar(
        val name: String,
        val type: String,
        val arraySize: Int?,
        val line: Int,
        val scalarComponents: Int,
        val vec4Registers: Int,
        val isParameter: Boolean,
        var firstDefStmt: Int,
        var lastUseStmt: Int,
        var readCount: Int = 0,
        var writeCount: Int = 1,
        var dynamicallyIndexed: Boolean = false,
    )

    private fun analyzeFunction(
        file: PackFile,
        cleanText: String,
        fn: ParsedFunction,
        isFragment: Boolean,
        versionId: String,
        structs: Map<String, GlslStruct>,
        baseNonUniforms: Set<String>,
        maxVec4Temps: Int,
        out: MutableList<Finding>,
    ): FunctionPressureReport {
        val bodyNoPreproc = blankPreprocessorLines(fn.bodyText)
        val varsByName = linkedMapOf<String, TrackedVar>()
        val nonUniforms = baseNonUniforms.toMutableSet()
        val loops = mutableListOf<IntRange>()
        val stmtLines = mutableListOf<Int>()
        val reportedDivergentLines = mutableSetOf<Int>()

        fun nextStmt(line: Int): Int {
            val idx = stmtLines.size
            stmtLines += line
            return idx
        }

        val entryStmt = nextStmt(fn.headerLine)
        parseParameters(fn.paramsText, fn.headerLine, structs).forEach { p ->
            varsByName[p.name] = p
        }

        fun registerReadsInExpr(expr: String, stmtIdx: Int, excludeIdent: String? = null) {
            for (m in TOKEN_IDENT.findAll(expr)) {
                val id = m.groupValues[1]
                if (id == excludeIdent) continue
                val v = varsByName[id] ?: continue
                v.readCount++
                v.lastUseStmt = max(v.lastUseStmt, stmtIdx)
            }
            for ((name, v) in varsByName) {
                if (v.arraySize != null && v.arraySize > 1) {
                    val bracketRe = Regex("\\b${Regex.escape(name)}\\s*\\[([^\\]]+)\\]")
                    for (bm in bracketRe.findAll(expr)) {
                        val idxExpr = bm.groupValues[1].trim()
                        if (!Regex("^\\d+[uU]?$").matches(idxExpr)) {
                            v.dynamicallyIndexed = true
                        }
                    }
                }
            }
        }

        fun exprIsNonUniform(expr: String): Boolean {
            for (m in TOKEN_IDENT.findAll(expr)) {
                if (m.groupValues[1] in nonUniforms) return true
            }
            for (fm in FUNC_CALL.findAll(expr)) {
                if (fm.groupValues[1] in TAINTING_FETCH_FUNCS) return true
            }
            return false
        }

        fun checkDivergentDerivatives(expr: String, line: Int, divergent: Boolean) {
            if (!isFragment || !divergent) return
            for (fm in FUNC_CALL.findAll(expr)) {
                val fnCall = fm.groupValues[1]
                if (fnCall in IMPLICIT_DERIVATIVE_FUNCS && reportedDivergentLines.add(line)) {
                    val hint = if (fnCall.startsWith("texture")) {
                        "Sample with explicit textureLod() or textureGrad(), or hoist the sample above the divergent branch."
                    } else {
                        "Hoist derivative evaluation outside the non-uniform branch so all 2x2 quad invocations execute it."
                    }
                    out += Finding(
                        rule = "SM078",
                        severity = Severity.WARNING,
                        version = versionId,
                        file = file.physicalRelative,
                        line = line,
                        message = "Implicit screen-space derivative '$fnCall(...)' inside non-uniform conditional control flow in '${fn.name}()' produces undefined mipmap/quad derivatives on divergent fragments. $hint",
                    )
                }
            }
        }

        fun processSimpleStatement(rawStmt: String, stmtOffsetInBody: Int, divergent: Boolean) {
            val trimmed = rawStmt.trim()
            if (trimmed.isEmpty()) return
            val firstNonWs = rawStmt.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val line = lineAt(cleanText, fn.bodyOffset + stmtOffsetInBody + firstNonWs)
            val stmtIdx = nextStmt(line)

            checkDivergentDerivatives(trimmed, line, divergent)

            val declParsed = tryParseLocalDeclaration(trimmed, structs)
            if (declParsed != null) {
                val (baseType, declarators) = declParsed
                for (d in declarators) {
                    if (d.initializer != null) {
                        registerReadsInExpr(d.initializer, stmtIdx)
                    }
                    val (scalars, vec4s) = footprintFor(baseType, d.arraySize, structs)
                    val tv = TrackedVar(
                        name = d.name,
                        type = if (d.arraySize != null) "$baseType[${d.arraySize}]" else baseType,
                        arraySize = d.arraySize,
                        line = line,
                        scalarComponents = scalars,
                        vec4Registers = vec4s,
                        isParameter = false,
                        firstDefStmt = stmtIdx,
                        lastUseStmt = stmtIdx,
                    )
                    varsByName[d.name] = tv
                    if (divergent || (d.initializer != null && exprIsNonUniform(d.initializer))) {
                        nonUniforms += d.name
                    }
                }
                return
            }

            val assignSplit = splitTopLevelAssignment(trimmed)
            if (assignSplit != null) {
                val (lhs, op, rhs) = assignSplit
                registerReadsInExpr(rhs, stmtIdx)
                val lhsRoot = TOKEN_IDENT.find(lhs)?.groupValues?.get(1)
                val targetVar = lhsRoot?.let { varsByName[it] }
                if (targetVar != null) {
                    targetVar.writeCount++
                    val isCompoundOrPartial = op != "=" || lhs.contains('.') || lhs.contains('[')
                    if (isCompoundOrPartial) {
                        registerReadsInExpr(lhs, stmtIdx)
                    } else {
                        targetVar.lastUseStmt = max(targetVar.lastUseStmt, stmtIdx)
                    }
                    if (divergent || exprIsNonUniform(rhs)) {
                        nonUniforms += targetVar.name
                    }
                } else {
                    registerReadsInExpr(lhs, stmtIdx)
                }
                return
            }

            registerReadsInExpr(trimmed, stmtIdx)
        }

        fun scanRegion(start: Int, end: Int, divergent: Boolean) {
            var pos = start
            var lastIfWasDivergent = false

            while (pos < end) {
                while (pos < end && bodyNoPreproc[pos].isWhitespace()) pos++
                if (pos >= end) break

                if (bodyNoPreproc[pos] == ';') {
                    pos++
                    lastIfWasDivergent = false
                    continue
                }
                if (bodyNoPreproc[pos] == '{') {
                    val closeBrace = findMatchingBrace(bodyNoPreproc, pos, end)
                    scanRegion(pos + 1, closeBrace, divergent)
                    pos = (closeBrace + 1).coerceAtMost(end)
                    lastIfWasDivergent = false
                    continue
                }

                val kwMatch = Regex("^(for|while|do|if|else|switch)\\b").find(bodyNoPreproc.substring(pos, end))
                if (kwMatch != null) {
                    val kw = kwMatch.groupValues[1]
                    val kwLine = lineAt(cleanText, fn.bodyOffset + pos)
                    var cursor = pos + kw.length
                    while (cursor < end && bodyNoPreproc[cursor].isWhitespace()) cursor++

                    when (kw) {
                        "for" -> {
                            lastIfWasDivergent = false
                            val loopStartStmt = stmtLines.size
                            if (cursor < end && bodyNoPreproc[cursor] == '(') {
                                val closeParen = findMatchingParen(bodyNoPreproc, cursor, end)
                                val headerInside = bodyNoPreproc.substring(cursor + 1, closeParen)
                                val parts = splitTopLevelChar(headerInside, ';')
                                if (parts.isNotEmpty()) {
                                    processSimpleStatement(parts[0], cursor + 1, divergent)
                                }
                                for (k in 1 until parts.size) {
                                    val sIdx = nextStmt(kwLine)
                                    checkDivergentDerivatives(parts[k], kwLine, divergent)
                                    registerReadsInExpr(parts[k], sIdx)
                                }
                                cursor = closeParen + 1
                            }
                            pos = scanControlledBody(bodyNoPreproc, cursor, end, divergent, ::scanRegion, ::processSimpleStatement)
                            val loopEndStmt = (stmtLines.size - 1).coerceAtLeast(loopStartStmt)
                            loops += loopStartStmt..loopEndStmt
                            continue
                        }
                        "while" -> {
                            lastIfWasDivergent = false
                            val loopStartStmt = nextStmt(kwLine)
                            var condDivergent = divergent
                            if (cursor < end && bodyNoPreproc[cursor] == '(') {
                                val closeParen = findMatchingParen(bodyNoPreproc, cursor, end)
                                val condText = bodyNoPreproc.substring(cursor + 1, closeParen)
                                checkDivergentDerivatives(condText, kwLine, divergent)
                                registerReadsInExpr(condText, loopStartStmt)
                                if (exprIsNonUniform(condText)) condDivergent = true
                                cursor = closeParen + 1
                            }
                            pos = scanControlledBody(bodyNoPreproc, cursor, end, condDivergent, ::scanRegion, ::processSimpleStatement)
                            val loopEndStmt = (stmtLines.size - 1).coerceAtLeast(loopStartStmt)
                            loops += loopStartStmt..loopEndStmt
                            continue
                        }
                        "do" -> {
                            lastIfWasDivergent = false
                            val loopStartStmt = stmtLines.size
                            var afterBody = scanControlledBody(bodyNoPreproc, cursor, end, divergent, ::scanRegion, ::processSimpleStatement)
                            while (afterBody < end && bodyNoPreproc[afterBody].isWhitespace()) afterBody++
                            val trailingWhile = Regex("^while\\b").find(bodyNoPreproc.substring(afterBody, end))
                            if (trailingWhile != null) {
                                var wCur = afterBody + 5
                                while (wCur < end && bodyNoPreproc[wCur].isWhitespace()) wCur++
                                if (wCur < end && bodyNoPreproc[wCur] == '(') {
                                    val closeP = findMatchingParen(bodyNoPreproc, wCur, end)
                                    val condText = bodyNoPreproc.substring(wCur + 1, closeP)
                                    val wLine = lineAt(cleanText, fn.bodyOffset + afterBody)
                                    val wStmt = nextStmt(wLine)
                                    checkDivergentDerivatives(condText, wLine, divergent)
                                    registerReadsInExpr(condText, wStmt)
                                    wCur = closeP + 1
                                }
                                while (wCur < end && bodyNoPreproc[wCur].isWhitespace()) wCur++
                                if (wCur < end && bodyNoPreproc[wCur] == ';') wCur++
                                afterBody = wCur
                            }
                            pos = afterBody
                            val loopEndStmt = (stmtLines.size - 1).coerceAtLeast(max(loopStartStmt, entryStmt))
                            loops += loopStartStmt..loopEndStmt
                            continue
                        }
                        "if", "switch" -> {
                            val condStmt = nextStmt(kwLine)
                            var branchDiv = divergent
                            if (cursor < end && bodyNoPreproc[cursor] == '(') {
                                val closeParen = findMatchingParen(bodyNoPreproc, cursor, end)
                                val condText = bodyNoPreproc.substring(cursor + 1, closeParen)
                                checkDivergentDerivatives(condText, kwLine, divergent)
                                registerReadsInExpr(condText, condStmt)
                                if (exprIsNonUniform(condText)) branchDiv = true
                                cursor = closeParen + 1
                            }
                            pos = scanControlledBody(bodyNoPreproc, cursor, end, branchDiv, ::scanRegion, ::processSimpleStatement)
                            lastIfWasDivergent = branchDiv
                            continue
                        }
                        "else" -> {
                            val elseDiv = divergent || lastIfWasDivergent
                            pos = scanControlledBody(bodyNoPreproc, cursor, end, elseDiv, ::scanRegion, ::processSimpleStatement)
                            continue
                        }
                    }
                }

                val stmtEnd = findStatementEnd(bodyNoPreproc, pos, end)
                val rawStmt = bodyNoPreproc.substring(pos, stmtEnd)
                processSimpleStatement(rawStmt, pos, divergent)
                pos = (stmtEnd + 1).coerceAtMost(end)
                lastIfWasDivergent = false
            }
        }

        scanRegion(0, bodyNoPreproc.length, divergent = false)

        // Extend live ranges across enclosing loop back-edges until convergence.
        var changed = true
        while (changed) {
            changed = false
            for (loopRange in loops) {
                for (v in varsByName.values) {
                    if (v.readCount > 0 && v.firstDefStmt <= loopRange.first && v.lastUseStmt in loopRange) {
                        if (v.lastUseStmt < loopRange.last) {
                            v.lastUseStmt = loopRange.last
                            changed = true
                        }
                    }
                }
            }
        }

        // Sweep-line peak temporary register calculation.
        val totalStmts = stmtLines.size.coerceAtLeast(1)
        var peakVec4 = 0
        var peakScalars = 0
        var peakStmt = 0
        var liveAtPeak: List<String> = emptyList()

        val trackedList = varsByName.values.toList()
        for (s in 0 until totalStmts) {
            var curVec4 = 0
            var curScalars = 0
            val activeNames = mutableListOf<String>()
            for (v in trackedList) {
                val endActive = if (v.readCount > 0) v.lastUseStmt else v.firstDefStmt
                if (v.isParameter && v.readCount == 0) continue
                if (s in v.firstDefStmt..endActive) {
                    curVec4 += v.vec4Registers
                    curScalars += v.scalarComponents
                    activeNames += v.name
                }
            }
            if (curVec4 > peakVec4 || (curVec4 == peakVec4 && curScalars > peakScalars)) {
                peakVec4 = curVec4
                peakScalars = curScalars
                peakStmt = s
                liveAtPeak = activeNames
            }
        }

        val peakLine = stmtLines.getOrElse(peakStmt) { fn.headerLine }
        val occupancy = estimateOccupancyPct(peakVec4)

        if (peakVec4 > maxVec4Temps) {
            val severity = if (peakVec4 > maxVec4Temps * 2) Severity.ERROR else Severity.WARNING
            val sampleVars = liveAtPeak.take(6).joinToString(", ")
            out += Finding(
                rule = "SM077",
                severity = severity,
                version = versionId,
                file = file.physicalRelative,
                line = peakLine,
                message = "Function '${fn.name}()' reaches $peakVec4 live vec4 registers ($peakScalars scalar components, ~${occupancy}% SIMD wave occupancy) at line $peakLine, exceeding the $maxVec4Temps-vec4 budget (live: $sampleVars).",
            )
        }

        for (v in trackedList) {
            if (!v.isParameter && v.dynamicallyIndexed && v.vec4Registers >= 4) {
                val sev = if (v.vec4Registers >= 16) Severity.ERROR else Severity.WARNING
                out += Finding(
                    rule = "SM077",
                    severity = sev,
                    version = versionId,
                    file = file.physicalRelative,
                    line = v.line,
                    message = "Local array '${v.type} ${v.name}' (${v.vec4Registers} vec4 slots) in '${fn.name}()' is indexed with a non-constant expression, forcing GPU VGPR spilling to slow private scratch memory. Prefer a uniform/SSBO LUT or constant unrolled indexing.",
                )
            }
            if (!v.isParameter && v.readCount == 0 && !v.name.startsWith("_")) {
                out += Finding(
                    rule = "SM079",
                    severity = Severity.NOTE,
                    version = versionId,
                    file = file.physicalRelative,
                    line = v.line,
                    message = "Local variable '${v.type} ${v.name}' in '${fn.name}()' is assigned but never read.",
                )
            }
        }

        return FunctionPressureReport(
            functionName = fn.name,
            line = fn.headerLine,
            statementCount = totalStmts,
            loopCount = loops.size,
            peakVec4Registers = peakVec4,
            peakScalarComponents = peakScalars,
            peakLine = peakLine,
            estimatedOccupancyPct = occupancy,
            liveAtPeak = liveAtPeak,
            variables = trackedList.map { v ->
                LiveVariableSummary(
                    name = v.name,
                    type = v.type,
                    line = v.line,
                    scalarComponents = v.scalarComponents,
                    vec4Registers = v.vec4Registers,
                    firstStmt = v.firstDefStmt,
                    lastStmt = if (v.readCount > 0) v.lastUseStmt else v.firstDefStmt,
                    readCount = v.readCount,
                    dynamicallyIndexed = v.dynamicallyIndexed,
                )
            },
        )
    }

    private fun parseParameters(
        paramsText: String,
        headerLine: Int,
        structs: Map<String, GlslStruct>,
    ): List<TrackedVar> {
        if (paramsText.isEmpty() || paramsText == "void") return emptyList()
        return splitTopLevelChar(paramsText, ',').mapNotNull { rawParam ->
            val tokens = rawParam.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it !in PRECISION_OR_CONST }
            if (tokens.size < 2) return@mapNotNull null
            val rawType = tokens[tokens.size - 2]
            val rawName = tokens.last()
            val baseType = rawType.substringBefore('[')
            val name = rawName.substringBefore('[')
            if (!GLSL_Ident.matches(name)) return@mapNotNull null
            val arrSize = extractBracketInt(rawType) ?: extractBracketInt(rawName)
            val (scalars, vec4s) = footprintFor(baseType, arrSize, structs)
            TrackedVar(
                name = name,
                type = if (arrSize != null) "$baseType[$arrSize]" else baseType,
                arraySize = arrSize,
                line = headerLine,
                scalarComponents = scalars,
                vec4Registers = vec4s,
                isParameter = true,
                firstDefStmt = 0,
                lastUseStmt = 0,
            )
        }
    }

    private data class LocalDeclarator(
        val name: String,
        val arraySize: Int?,
        val initializer: String?,
    )

    private fun tryParseLocalDeclaration(
        stmt: String,
        structs: Map<String, GlslStruct>,
    ): Pair<String, List<LocalDeclarator>>? {
        var rest = stmt.trim()
        while (true) {
            val firstWord = rest.substringBefore(' ').substringBefore('\t')
            if (firstWord in PRECISION_OR_CONST && firstWord.length < rest.length) {
                rest = rest.substring(firstWord.length).trimStart()
            } else {
                break
            }
        }
        val typeMatch = Regex("^([A-Za-z_]\\w*)(?:\\s*\\[\\s*(\\d+)\\s*\\])?\\s+(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(rest)
            ?: return null
        val typeName = typeMatch.groupValues[1]
        if (typeName !in SCALAR_COMPONENTS_BY_TYPE && typeName !in structs) return null
        val typeArrSize = typeMatch.groupValues[2].toIntOrNull()
        val declListText = typeMatch.groupValues[3].trim()

        val parts = splitTopLevelChar(declListText, ',')
        val declarators = mutableListOf<LocalDeclarator>()
        for (part in parts) {
            val eqIdx = findTopLevelSingleEquals(part)
            val lhs = (if (eqIdx >= 0) part.substring(0, eqIdx) else part).trim()
            val rhs = if (eqIdx >= 0) part.substring(eqIdx + 1).trim() else null
            val m = Regex("^([A-Za-z_]\\w*)(?:\\s*\\[\\s*(\\d+)?\\s*\\])?$").matchEntire(lhs) ?: return null
            val varName = m.groupValues[1]
            val varArr = m.groupValues[2].toIntOrNull() ?: typeArrSize
            declarators += LocalDeclarator(varName, varArr, rhs)
        }
        return if (declarators.isEmpty()) null else (typeName to declarators)
    }

    private fun footprintFor(
        baseType: String,
        arraySize: Int?,
        structs: Map<String, GlslStruct>,
        visited: Set<String> = emptySet(),
    ): Pair<Int, Int> {
        val count = (arraySize ?: 1).coerceAtLeast(1)
        SCALAR_COMPONENTS_BY_TYPE[baseType]?.let { sc ->
            val v4 = VEC4_SLOTS_BY_TYPE[baseType] ?: ceil(sc / 4.0).toInt().coerceAtLeast(1)
            return (sc * count) to (v4 * count)
        }
        val st = structs[baseType]
        if (st != null && baseType !in visited) {
            var scSum = 0
            var v4Sum = 0
            for (field in st.fields) {
                val (fSc, fV4) = footprintFor(field.type, field.arraySize, structs, visited + baseType)
                scSum += fSc
                v4Sum += fV4
            }
            return (scSum.coerceAtLeast(1) * count) to (v4Sum.coerceAtLeast(1) * count)
        }
        return (4 * count) to (1 * count)
    }

    fun estimateOccupancyPct(peakVec4Regs: Int): Int = when {
        peakVec4Regs <= 16 -> 100
        peakVec4Regs <= 24 -> 75
        peakVec4Regs <= 32 -> 50
        peakVec4Regs <= 48 -> 37
        peakVec4Regs <= 64 -> 25
        else -> 12
    }

    private fun scanControlledBody(
        text: String,
        startCursor: Int,
        end: Int,
        divergent: Boolean,
        scanRegion: (Int, Int, Boolean) -> Unit,
        processSimple: (String, Int, Boolean) -> Unit,
    ): Int {
        var cur = startCursor
        while (cur < end && text[cur].isWhitespace()) cur++
        if (cur >= end) return end
        return if (text[cur] == '{') {
            val close = findMatchingBrace(text, cur, end)
            scanRegion(cur + 1, close, divergent)
            (close + 1).coerceAtMost(end)
        } else {
            // Check nested control keyword directly as single-statement body (e.g. `else if (...)`)
            val nestedKw = Regex("^(if|for|while|do|switch)\\b").find(text.substring(cur, end))
            if (nestedKw != null) {
                val subEnd = findSingleControlStatementEnd(text, cur, end)
                scanRegion(cur, subEnd, divergent)
                subEnd
            } else {
                val stmtEnd = findStatementEnd(text, cur, end)
                processSimple(text.substring(cur, stmtEnd), cur, divergent)
                (stmtEnd + 1).coerceAtMost(end)
            }
        }
    }

    private fun findSingleControlStatementEnd(text: String, start: Int, end: Int): Int {
        var cur = start
        val kw = Regex("^(if|for|while|switch)\\b").find(text.substring(cur, end))?.groupValues?.get(1)
        if (kw != null) {
            cur += kw.length
            while (cur < end && text[cur].isWhitespace()) cur++
            if (cur < end && text[cur] == '(') {
                cur = (findMatchingParen(text, cur, end) + 1).coerceAtMost(end)
            }
            while (cur < end && text[cur].isWhitespace()) cur++
            cur = if (cur < end && text[cur] == '{') {
                (findMatchingBrace(text, cur, end) + 1).coerceAtMost(end)
            } else {
                (findStatementEnd(text, cur, end) + 1).coerceAtMost(end)
            }
            if (kw == "if") {
                var probe = cur
                while (probe < end && text[probe].isWhitespace()) probe++
                if (Regex("^else\\b").containsMatchIn(text.substring(probe, end))) {
                    probe += 4
                    while (probe < end && text[probe].isWhitespace()) probe++
                    cur = if (probe < end && text[probe] == '{') {
                        (findMatchingBrace(text, probe, end) + 1).coerceAtMost(end)
                    } else {
                        findSingleControlStatementEnd(text, probe, end)
                    }
                }
            }
            return cur
        }
        return (findStatementEnd(text, start, end) + 1).coerceAtMost(end)
    }

    private fun findMatchingBrace(text: String, openIdx: Int, end: Int): Int {
        var depth = 1
        var i = openIdx + 1
        while (i < end && depth > 0) {
            if (text[i] == '{') depth++
            else if (text[i] == '}') depth--
            i++
        }
        return (i - 1).coerceAtLeast(openIdx)
    }

    private fun findMatchingParen(text: String, openIdx: Int, end: Int): Int {
        var depth = 1
        var i = openIdx + 1
        while (i < end && depth > 0) {
            if (text[i] == '(') depth++
            else if (text[i] == ')') depth--
            i++
        }
        return (i - 1).coerceAtLeast(openIdx)
    }

    private fun findStatementEnd(text: String, start: Int, end: Int): Int {
        var paren = 0
        var bracket = 0
        var i = start
        while (i < end) {
            when (text[i]) {
                '(' -> paren++
                ')' -> paren = (paren - 1).coerceAtLeast(0)
                '[' -> bracket++
                ']' -> bracket = (bracket - 1).coerceAtLeast(0)
                ';' -> if (paren == 0 && bracket == 0) return i
                '}' -> if (paren == 0 && bracket == 0) return i
            }
            i++
        }
        return end
    }

    private fun splitTopLevelChar(text: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        var paren = 0
        var bracket = 0
        var brace = 0
        var last = 0
        for (i in text.indices) {
            when (text[i]) {
                '(' -> paren++
                ')' -> paren = (paren - 1).coerceAtLeast(0)
                '[' -> bracket++
                ']' -> bracket = (bracket - 1).coerceAtLeast(0)
                '{' -> brace++
                '}' -> brace = (brace - 1).coerceAtLeast(0)
                delimiter -> if (paren == 0 && bracket == 0 && brace == 0) {
                    out += text.substring(last, i)
                    last = i + 1
                }
            }
        }
        out += text.substring(last)
        return out
    }

    private fun findTopLevelSingleEquals(text: String): Int {
        var paren = 0
        var bracket = 0
        for (i in text.indices) {
            when (text[i]) {
                '(' -> paren++
                ')' -> paren = (paren - 1).coerceAtLeast(0)
                '[' -> bracket++
                ']' -> bracket = (bracket - 1).coerceAtLeast(0)
                '=' -> if (paren == 0 && bracket == 0) {
                    val prev = if (i > 0) text[i - 1] else ' '
                    val next = if (i + 1 < text.length) text[i + 1] else ' '
                    if (prev !in charArrayOf('=', '!', '<', '>') && next != '=') return i
                }
            }
        }
        return -1
    }

    private fun splitTopLevelAssignment(stmt: String): Triple<String, String, String>? {
        var paren = 0
        var bracket = 0
        var i = 0
        while (i < stmt.length) {
            when (stmt[i]) {
                '(' -> paren++
                ')' -> paren = (paren - 1).coerceAtLeast(0)
                '[' -> bracket++
                ']' -> bracket = (bracket - 1).coerceAtLeast(0)
                '=' -> if (paren == 0 && bracket == 0) {
                    val next = if (i + 1 < stmt.length) stmt[i + 1] else ' '
                    val prev = if (i > 0) stmt[i - 1] else ' '
                    if (next == '=' || prev in charArrayOf('=', '!', '<', '>')) {
                        i++
                        continue
                    }
                    val opStart = if (prev in charArrayOf('+', '-', '*', '/', '%', '&', '|', '^')) i - 1 else i
                    val op = stmt.substring(opStart, i + 1)
                    return Triple(stmt.substring(0, opStart).trim(), op, stmt.substring(i + 1).trim())
                }
            }
            i++
        }
        return null
    }

    private fun extractBracketInt(token: String): Int? =
        Regex("\\[\\s*(\\d+)\\s*\\]").find(token)?.groupValues?.get(1)?.toIntOrNull()

    private fun blankPreprocessorLines(body: String): String =
        body.lines().joinToString("\n") { line ->
            if (line.trimStart().startsWith("#")) " ".repeat(line.length) else line
        }

    private fun isAtLineStart(s: String, idx: Int): Boolean {
        var k = idx - 1
        while (k >= 0 && s[k] != '\n') {
            if (!s[k].isWhitespace()) return false
            k--
        }
        return true
    }

    private fun lineAt(s: String, offset: Int): Int =
        s.substring(0, offset.coerceIn(0, s.length)).count { it == '\n' } + 1
}
