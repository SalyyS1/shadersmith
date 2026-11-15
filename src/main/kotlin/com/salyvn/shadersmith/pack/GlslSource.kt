package com.salyvn.shadersmith.pack

import com.salyvn.shadersmith.contract.BlockMember

/** A `layout(std140) uniform Name { ... }` block as declared in a source file, with the line it starts on. */
data class DeclaredBlock(val name: String, val members: List<BlockMember>, val line: Int)

/** An `#include` / `#moj_import` line: which directive, what it names, and where. */
data class IncludeDirective(val directive: String, val target: String, val line: Int)

/** An `#extension Name : behavior` directive. */
data class ExtensionDirective(val name: String, val behavior: String, val line: Int)

enum class BufferStorageKind { UNIFORM, BUFFER }

enum class PackingStandard { STD140, STD430, SHARED, PACKED }

/** One member inside a `uniform` or `buffer` (SSBO) interface block. */
data class BufferMemberDecl(
    val type: String,
    val name: String,
    val arraySize: Int? = null,
    val isUnsizedArray: Boolean = false,
    val arrayExpr: String? = null,
    val rowMajor: Boolean = false,
    val memoryQualifiers: Set<String> = emptySet(),
    val line: Int,
)

/** Full declaration of a `uniform` or `buffer` (SSBO) block with layout & memory qualifiers. */
data class BufferBlockDecl(
    val storage: BufferStorageKind,
    val name: String,
    val instanceName: String?,
    val packing: PackingStandard,
    val explicitPacking: String?,
    val binding: Int?,
    val hasInvalidBinding: Boolean = false,
    val rawBindingText: String? = null,
    val memoryQualifiers: Set<String>,
    val rowMajorDefault: Boolean,
    val members: List<BufferMemberDecl>,
    val line: Int,
)

/** One field of a GLSL `struct` definition. */
data class GlslStructField(
    val type: String,
    val name: String,
    val arraySize: Int? = null,
    val isUnsizedArray: Boolean = false,
    val rowMajor: Boolean = false,
    val line: Int,
)

/** A GLSL `struct Name { ... };` definition. */
data class GlslStruct(
    val name: String,
    val fields: List<GlslStructField>,
    val line: Int,
)

enum class SamplerValueKind { FLOAT, INT, UINT, SHADOW }

data class DeclaredSampler(
    val type: String,
    val name: String,
    val valueKind: SamplerValueKind,
    val line: Int,
)

enum class IoDirection { IN, OUT }

enum class Interpolation { SMOOTH, FLAT, NOPERSPECTIVE }

/**
 * One active preprocessor conditional branch clause enclosing a declaration.
 *
 * Two declarations cannot be simultaneously active in a single shader compilation pass if they share an
 * `ifBlockId` with distinct `branchIndex` values (`#ifdef` vs `#elif` vs `#else`) or carry opposite
 * polarity on the same simple macro identifier (`+FOO` vs `-FOO`).
 */
data class PreprocessorClause(
    val ifBlockId: Int,
    val branchIndex: Int,
    val macroSign: Pair<String, Boolean>? = null,
    val rawCondition: String,
)

/** A file-scope `in` or `out` stage interface variable, with its layout qualifiers and preprocessor guard. */
data class StageInterfaceVar(
    val direction: IoDirection,
    val location: Int?,
    val component: Int?,
    val interpolation: Interpolation,
    val explicitInterpolation: Boolean,
    val type: String,
    val name: String,
    val arraySize: Int? = null,
    val arrayExpr: String? = null,
    val guard: List<PreprocessorClause>,
    val line: Int,
)

/**
 * The parts of a GLSL file that the version contract, buffer layout calculator, and pipeline linker inspect.
 *
 * Line-preserving comment stripping removes `//` and block comments first so commented-out `#moj_import`,
 * `#version`, `gl_VertexID`, or `std140`/`std430` blocks never trigger false findings while every line
 * number stays exact.
 */
class GlslSource(val text: String) {
    private val rawLines: List<String> = text.lines()
    internal val cleanText: String = stripCommentsPreservingLines(text)
    private val cleanLines: List<String> = cleanText.lines()

    /** The `#version N` line, if the file has one. */
    val version: Pair<Int, Int>? by lazy {
        cleanLines.withIndex().firstNotNullOfOrNull { (index, line) ->
            VERSION.find(line)?.let { match -> match.groupValues[1].toInt() to index + 1 }
        }
    }

    val extensions: List<ExtensionDirective> by lazy {
        cleanLines.withIndex().mapNotNull { (index, line) ->
            EXTENSION.find(line)?.let { match ->
                ExtensionDirective(match.groupValues[1], match.groupValues[2], index + 1)
            }
        }
    }

    val includes: List<IncludeDirective> by lazy {
        cleanLines.withIndex().mapNotNull { (index, line) ->
            INCLUDE.find(line)?.let { match -> IncludeDirective(match.groupValues[1], match.groupValues[2], index + 1) }
        }
    }

    private val parsedTopLevel: ParsedTopLevel by lazy { parseTopLevelConstructs() }

    val structs: List<GlslStruct> by lazy { parsedTopLevel.structs }

    val bufferBlocks: List<BufferBlockDecl> by lazy { parsedTopLevel.bufferBlocks }

    /** Executable function bodies concatenated without comments or block declarations, for read/write checks. */
    val executableCode: String by lazy { parsedTopLevel.executableCode }

    /** `layout(std140) uniform` blocks in declaration order, preserving backward-compatible contract checks. */
    val blocks: List<DeclaredBlock> by lazy {
        bufferBlocks
            .filter { it.storage == BufferStorageKind.UNIFORM && it.packing == PackingStandard.STD140 && it.explicitPacking == "std140" }
            .map { blk ->
                DeclaredBlock(
                    name = blk.name,
                    members = blk.members.map { BlockMember(it.type, it.name) },
                    line = blk.line,
                )
            }
    }

    /** `in`/`out` declarations at file scope that lack `layout(location = N)`, with their lines. */
    val unqualifiedInterfaceVariables: List<Pair<String, Int>> by lazy {
        cleanLines.withIndex().mapNotNull { (index, cleanLine) ->
            if (BARE_IO.matches(cleanLine.trim())) rawLines[index].trim() to index + 1 else null
        }
    }

    /** All file-scope `in` and `out` interface declarations with layout locations and preprocessor guards. */
    val interfaceVariables: List<StageInterfaceVar> by lazy { parseStageInterfaceVariables() }

    /** `gl_VertexID` occurrences outside comments, which 26.3 renamed to `gl_VertexIndex`. */
    val vertexIdLines: List<Int> by lazy {
        cleanLines.withIndex().filter { (_, line) -> VERTEX_ID.containsMatchIn(line) }.map { it.index + 1 }
    }

    /** Detailed sampler declarations (`sampler2D`, `isampler2D`, `usampler2D`, `sampler2DShadow`, etc.). */
    val declaredSamplers: List<DeclaredSampler> by lazy {
        SAMPLER_TYPED.findAll(cleanText).map { match ->
            val samplerType = match.groupValues[1]
            val samplerName = match.groupValues[2]
            val kind = when {
                samplerType.endsWith("Shadow") -> SamplerValueKind.SHADOW
                samplerType.startsWith("isampler") -> SamplerValueKind.INT
                samplerType.startsWith("usampler") -> SamplerValueKind.UINT
                else -> SamplerValueKind.FLOAT
            }
            DeclaredSampler(samplerType, samplerName, kind, cleanText.lineNumberAt(match.range.first))
        }.toList()
    }

    /** `uniform [i|u]sampler* Name;` variable names outside comments. */
    val samplers: List<String> by lazy { declaredSamplers.map { it.name } }

    fun hasExtensionEnabled(extensionName: String): Boolean =
        extensions.any { it.name == extensionName && it.behavior in ENABLED_BEHAVIORS }

    private data class ParsedTopLevel(
        val structs: List<GlslStruct>,
        val bufferBlocks: List<BufferBlockDecl>,
        val executableCode: String,
    )

    private fun parseTopLevelConstructs(): ParsedTopLevel {
        val structsOut = mutableListOf<GlslStruct>()
        val blocksOut = mutableListOf<BufferBlockDecl>()
        val execOut = StringBuilder()

        var i = 0
        val n = cleanText.length
        var headerStart = 0

        while (i < n) {
            // Skip preprocessor lines at top level so they don't pollute declaration headers
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
                val header = cleanText.substring(headerStart, i)
                val bodyStart = i + 1
                var depth = 1
                i++
                while (i < n && depth > 0) {
                    if (cleanText[i] == '{') depth++
                    else if (cleanText[i] == '}') depth--
                    i++
                }
                val bodyEnd = (i - 1).coerceAtLeast(bodyStart)
                val body = cleanText.substring(bodyStart, bodyEnd)

                val trimmedHeader = header.trim()
                val headerOffset = headerStart + header.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                val headerLine = cleanText.lineNumberAt(headerOffset)

                // Check if followed by optional instance identifier before ';' (for struct/uniform/buffer blocks)
                val isFunction = trimmedHeader.endsWith(")")
                if (isFunction) {
                    execOut.append(body).append('\n')
                    headerStart = i
                    continue
                }

                val semiIdx = cleanText.indexOf(';', i)
                val trailer = if (semiIdx >= 0) cleanText.substring(i, semiIdx).trim() else ""
                if (semiIdx >= 0) {
                    i = semiIdx + 1
                }
                headerStart = i

                STRUCT_HEADER.matchEntire(trimmedHeader)?.let { sm ->
                    val structName = sm.groupValues[1]
                    val fields = parseStructFields(body, bodyStart)
                    structsOut += GlslStruct(structName, fields, headerLine)
                    continue
                }

                parseBufferOrUniformBlock(trimmedHeader, body, bodyStart, trailer, headerLine)?.let { blk ->
                    blocksOut += blk
                }
                continue
            }
            i++
        }

        return ParsedTopLevel(structsOut, blocksOut, execOut.toString())
    }

    private fun parseStructFields(body: String, bodyOffset: Int): List<GlslStructField> {
        val out = mutableListOf<GlslStructField>()
        var cursor = 0
        for (rawStmt in body.split(';')) {
            val stmtOffset = bodyOffset + cursor
            cursor += rawStmt.length + 1
            val cleaned = stripPreprocessorLines(rawStmt).trim()
            if (cleaned.isEmpty()) continue
            val firstChar = rawStmt.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val line = cleanText.lineNumberAt(stmtOffset + firstChar)
            val parsed = parseMemberStatement(cleaned, line) ?: continue
            out += GlslStructField(
                type = parsed.type,
                name = parsed.name,
                arraySize = parsed.arraySize,
                isUnsizedArray = parsed.isUnsizedArray,
                rowMajor = parsed.rowMajor,
                line = line,
            )
        }
        return out
    }

    private fun parseBufferOrUniformBlock(
        header: String,
        body: String,
        bodyOffset: Int,
        trailer: String,
        headerLine: Int,
    ): BufferBlockDecl? {
        var layoutContent: String? = null
        val withoutLayouts = LAYOUT_QUALIFIER.replace(header) { match ->
            layoutContent = if (layoutContent == null) match.groupValues[1] else "$layoutContent, ${match.groupValues[1]}"
            " "
        }.trim()

        val tokens = withoutLayouts.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        val blockName = tokens.last()
        if (!GLSL_IDENT.matches(blockName)) return null
        val prefixTokens = tokens.dropLast(1)

        val storage = when {
            "buffer" in prefixTokens -> BufferStorageKind.BUFFER
            "uniform" in prefixTokens -> BufferStorageKind.UNIFORM
            else -> return null
        }

        val memQuals = prefixTokens.filter { it in MEMORY_QUALIFIERS }.toMutableSet()
        var rowMajor = "row_major" in prefixTokens

        var explicitPacking: String? = null
        var binding: Int? = null
        var invalidBinding = false
        var rawBinding: String? = null

        layoutContent?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { item ->
            when {
                item in PACKING_NAMES -> explicitPacking = item
                item == "row_major" -> rowMajor = true
                item == "column_major" -> rowMajor = false
                item in MEMORY_QUALIFIERS -> memQuals += item
                item.startsWith("binding") -> {
                    val rhs = item.substringAfter('=', "").trim()
                    rawBinding = rhs
                    val parsed = rhs.toIntOrNull()
                    if (parsed == null || parsed < 0) {
                        invalidBinding = true
                        binding = parsed
                    } else {
                        binding = parsed
                    }
                }
            }
        }

        val packing = when (explicitPacking) {
            "std140" -> PackingStandard.STD140
            "std430" -> PackingStandard.STD430
            "packed" -> PackingStandard.PACKED
            "shared" -> PackingStandard.SHARED
            else -> PackingStandard.SHARED
        }

        val instanceName = trailer
            .substringBefore('[')
            .trim()
            .takeIf { it.isNotEmpty() && GLSL_IDENT.matches(it) }

        val members = mutableListOf<BufferMemberDecl>()
        var cursor = 0
        for (rawStmt in body.split(';')) {
            val stmtOffset = bodyOffset + cursor
            cursor += rawStmt.length + 1
            val cleaned = stripPreprocessorLines(rawStmt).trim()
            if (cleaned.isEmpty()) continue
            val firstChar = rawStmt.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val line = cleanText.lineNumberAt(stmtOffset + firstChar)
            parseMemberStatement(cleaned, line, rowMajor)?.let { members += it }
        }

        return BufferBlockDecl(
            storage = storage,
            name = blockName,
            instanceName = instanceName,
            packing = packing,
            explicitPacking = explicitPacking,
            binding = binding,
            hasInvalidBinding = invalidBinding,
            rawBindingText = rawBinding,
            memoryQualifiers = memQuals,
            rowMajorDefault = rowMajor,
            members = members,
            line = headerLine,
        )
    }

    private fun parseMemberStatement(
        stmt: String,
        line: Int,
        defaultRowMajor: Boolean = false,
    ): BufferMemberDecl? {
        var rowMajor = defaultRowMajor
        val memQuals = mutableSetOf<String>()
        val stripped = LAYOUT_QUALIFIER.replace(stmt) { m ->
            m.groupValues[1].split(',').map { it.trim() }.forEach { tok ->
                if (tok == "row_major") rowMajor = true
                if (tok == "column_major") rowMajor = false
            }
            " "
        }.trim()

        val match = MEMBER_FULL.matchEntire(stripped) ?: return null
        val qualsText = match.groupValues[1].trim()
        if (qualsText.isNotEmpty()) {
            qualsText.split(Regex("\\s+")).forEach { q ->
                if (q == "row_major") rowMajor = true
                if (q == "column_major") rowMajor = false
                if (q in MEMORY_QUALIFIERS) memQuals += q
            }
        }
        val type = match.groupValues[2]
        val bracketA = match.groupValues[3]
        val name = match.groupValues[4]
        val bracketB = match.groupValues[5]
        val hasBrackets = stripped.contains('[')
        val rawBracket = when {
            bracketA.isNotEmpty() -> bracketA.trim()
            bracketB.isNotEmpty() -> bracketB.trim()
            else -> ""
        }
        val isUnsized = hasBrackets && rawBracket.isEmpty()
        val fixedSize = if (hasBrackets && rawBracket.isNotEmpty()) rawBracket.toIntOrNull() else null
        return BufferMemberDecl(
            type = type,
            name = name,
            arraySize = fixedSize,
            isUnsizedArray = isUnsized,
            arrayExpr = rawBracket.ifEmpty { null },
            rowMajor = rowMajor,
            memoryQualifiers = memQuals,
            line = line,
        )
    }

    private fun parseStageInterfaceVariables(): List<StageInterfaceVar> {
        val out = mutableListOf<StageInterfaceVar>()
        val activeFrames = mutableListOf<PreprocessorClause>()
        var nextIfId = 1
        var braceDepth = 0

        for ((idx, line) in cleanLines.withIndex()) {
            val lineNum = idx + 1
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                when {
                    IFDEF.matches(trimmed) -> {
                        val macro = IFDEF.find(trimmed)!!.groupValues[1]
                        activeFrames += PreprocessorClause(nextIfId++, 0, macro to true, "defined($macro)")
                    }
                    IFNDEF.matches(trimmed) -> {
                        val macro = IFNDEF.find(trimmed)!!.groupValues[1]
                        activeFrames += PreprocessorClause(nextIfId++, 0, macro to false, "!defined($macro)")
                    }
                    IF_DIR.matches(trimmed) -> {
                        val expr = IF_DIR.find(trimmed)!!.groupValues[1].trim()
                        val simpleSign = parseSimpleDefinedSign(expr)
                        activeFrames += PreprocessorClause(nextIfId++, 0, simpleSign, expr)
                    }
                    ELIF_DIR.matches(trimmed) -> {
                        val expr = ELIF_DIR.find(trimmed)!!.groupValues[1].trim()
                        if (activeFrames.isNotEmpty()) {
                            val prev = activeFrames.removeAt(activeFrames.lastIndex)
                            activeFrames += PreprocessorClause(prev.ifBlockId, prev.branchIndex + 1, null, expr)
                        }
                    }
                    ELSE_DIR.matches(trimmed) -> {
                        if (activeFrames.isNotEmpty()) {
                            val prev = activeFrames.removeAt(activeFrames.lastIndex)
                            val flipped = prev.macroSign?.takeIf { prev.branchIndex == 0 }?.let { it.first to !it.second }
                            activeFrames += PreprocessorClause(prev.ifBlockId, prev.branchIndex + 1, flipped, "else(${prev.rawCondition})")
                        }
                    }
                    ENDIF_DIR.matches(trimmed) -> {
                        if (activeFrames.isNotEmpty()) activeFrames.removeAt(activeFrames.lastIndex)
                    }
                }
                continue
            }

            val depthBefore = braceDepth
            for (c in line) {
                if (c == '{') braceDepth++
                else if (c == '}') braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }
            if (depthBefore != 0 || line.contains('{')) continue
            if (!trimmed.endsWith(";")) continue

            parseInterfaceLine(trimmed, activeFrames.toList(), lineNum)?.let { out += it }
        }
        return out
    }

    private fun parseSimpleDefinedSign(expr: String): Pair<String, Boolean>? {
        SIMPLE_DEFINED.matchEntire(expr)?.let { return it.groupValues[1] to true }
        SIMPLE_NOT_DEFINED.matchEntire(expr)?.let { return it.groupValues[1] to false }
        return null
    }

    private fun parseInterfaceLine(
        trimmed: String,
        guard: List<PreprocessorClause>,
        line: Int,
    ): StageInterfaceVar? {
        var location: Int? = null
        var component: Int? = null
        val withoutLayout = LAYOUT_QUALIFIER.replace(trimmed) { match ->
            match.groupValues[1].split(',').map { it.trim() }.forEach { part ->
                val kv = part.split('=', limit = 2).map { it.trim() }
                if (kv.size == 2) {
                    if (kv[0] == "location") location = kv[1].toIntOrNull()
                    if (kv[0] == "component") component = kv[1].toIntOrNull()
                }
            }
            " "
        }.trim()

        val m = STAGE_IO_DECL.matchEntire(withoutLayout) ?: return null
        val quals = m.groupValues[1].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val direction = if (m.groupValues[2] == "in") IoDirection.IN else IoDirection.OUT
        val type = m.groupValues[3]
        val arrA = m.groupValues[4]
        val name = m.groupValues[5]
        val arrB = m.groupValues[6]
        val rawArr = (arrA.ifEmpty { arrB }).trim()

        val explicitInterp = quals.any { it in INTERPOLATION_QUALIFIERS }
        val interp = when {
            "flat" in quals -> Interpolation.FLAT
            "noperspective" in quals -> Interpolation.NOPERSPECTIVE
            else -> Interpolation.SMOOTH
        }

        return StageInterfaceVar(
            direction = direction,
            location = location,
            component = component,
            interpolation = interp,
            explicitInterpolation = explicitInterp,
            type = type,
            name = name,
            arraySize = rawArr.toIntOrNull(),
            arrayExpr = rawArr.ifEmpty { null },
            guard = guard,
            line = line,
        )
    }

    companion object {
        private val VERSION = Regex("^\\s*#version\\s+(\\d+)")
        private val EXTENSION = Regex("^\\s*#extension\\s+(\\w+)\\s*:\\s*(\\w+)")
        private val INCLUDE = Regex("^\\s*#(moj_import|include)\\s*[<\"]([^>\"]+)[>\"]")
        private val BARE_IO = Regex("^(?:(?:flat|smooth|noperspective|centroid)\\s+)*(in|out)\\s+\\w+\\s+\\w+\\s*;$")
        private val VERTEX_ID = Regex("\\bgl_VertexID\\b")
        private val SAMPLER_TYPED = Regex("\\buniform\\s+([iu]?sampler\\w*)\\s+(\\w+)\\s*;")
        private val GLSL_IDENT = Regex("^[A-Za-z_]\\w*$")
        private val STRUCT_HEADER = Regex("^struct\\s+([A-Za-z_]\\w*)$")
        private val LAYOUT_QUALIFIER = Regex("\\blayout\\s*\\(([^)]*)\\)")
        private val MEMBER_FULL = Regex(
            "^((?:(?:readonly|writeonly|coherent|volatile|restrict|row_major|column_major|highp|mediump|lowp)\\s+)*)" +
                "([A-Za-z_]\\w*)(?:\\s*\\[\\s*([^\\]]*)\\s*\\])?\\s+([A-Za-z_]\\w*)(?:\\s*\\[\\s*([^\\]]*)\\s*\\])?\\s*$",
        )
        private val STAGE_IO_DECL = Regex(
            "^((?:(?:flat|smooth|noperspective|centroid|sample|invariant|highp|mediump|lowp)\\s+)*)" +
                "(in|out)\\s+([A-Za-z_]\\w*)(?:\\s*\\[\\s*([^\\]]*)\\s*\\])?\\s+([A-Za-z_]\\w*)(?:\\s*\\[\\s*([^\\]]*)\\s*\\])?\\s*;$",
        )

        private val IFDEF = Regex("^#\\s*ifdef\\s+([A-Za-z_]\\w*)\\b.*$")
        private val IFNDEF = Regex("^#\\s*ifndef\\s+([A-Za-z_]\\w*)\\b.*$")
        private val IF_DIR = Regex("^#\\s*if\\s+(.+)$")
        private val ELIF_DIR = Regex("^#\\s*elif\\s+(.+)$")
        private val ELSE_DIR = Regex("^#\\s*else\\b.*$")
        private val ENDIF_DIR = Regex("^#\\s*endif\\b.*$")
        private val SIMPLE_DEFINED = Regex("^defined\\s*\\(?\\s*([A-Za-z_]\\w*)\\s*\\)?$")
        private val SIMPLE_NOT_DEFINED = Regex("^!\\s*defined\\s*\\(?\\s*([A-Za-z_]\\w*)\\s*\\)?$")

        private val ENABLED_BEHAVIORS = setOf("require", "enable", "warn")
        private val PACKING_NAMES = setOf("std140", "std430", "shared", "packed")
        private val MEMORY_QUALIFIERS = setOf("readonly", "writeonly", "coherent", "volatile", "restrict")
        private val INTERPOLATION_QUALIFIERS = setOf("flat", "smooth", "noperspective")

        internal fun String.lineNumberAt(offset: Int): Int =
            substring(0, offset.coerceIn(0, length)).count { it == '\n' } + 1

        private fun isAtLineStart(s: String, idx: Int): Boolean {
            var k = idx - 1
            while (k >= 0 && s[k] != '\n') {
                if (!s[k].isWhitespace()) return false
                k--
            }
            return true
        }

        private fun stripPreprocessorLines(s: String): String =
            s.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

        /** Check if two preprocessor guard chains are guaranteed mutually exclusive. */
        fun areMutuallyExclusive(a: List<PreprocessorClause>, b: List<PreprocessorClause>): Boolean {
            for (ca in a) {
                for (cb in b) {
                    if (ca.ifBlockId == cb.ifBlockId && ca.branchIndex != cb.branchIndex) return true
                    val sa = ca.macroSign
                    val sb = cb.macroSign
                    if (sa != null && sb != null && sa.first == sb.first && sa.second != sb.second) return true
                }
            }
            return false
        }

        /** Replaces `//...` and multi-line block comments with spaces while keeping every `\n` in place. */
        internal fun stripCommentsPreservingLines(input: String): String {
            val out = StringBuilder(input.length)
            var i = 0
            val n = input.length
            while (i < n) {
                val c = input[i]
                if (c == '/' && i + 1 < n && input[i + 1] == '/') {
                    out.append("  ")
                    i += 2
                    while (i < n && input[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                } else if (c == '/' && i + 1 < n && input[i + 1] == '*') {
                    out.append("  ")
                    i += 2
                    while (i < n) {
                        if (input[i] == '*' && i + 1 < n && input[i + 1] == '/') {
                            out.append("  ")
                            i += 2
                            break
                        }
                        out.append(if (input[i] == '\n') '\n' else ' ')
                        i++
                    }
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
