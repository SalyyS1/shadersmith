package com.salyvn.shadersmith.layout

import com.salyvn.shadersmith.pack.BufferBlockDecl
import com.salyvn.shadersmith.pack.BufferMemberDecl
import com.salyvn.shadersmith.pack.BufferStorageKind
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.GlslStruct
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackingStandard
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Severity
import kotlinx.serialization.Serializable

@Serializable
data class ComputedMemberLayout(
    val name: String,
    val type: String,
    val offset: Int,
    val size: Int,
    val endOffset: Int,
    val baseAlignment: Int,
    val paddingBefore: Int,
    val arraySize: Int? = null,
    val isUnsizedArray: Boolean = false,
    val arrayStride: Int? = null,
    val matrixStride: Int? = null,
    val vec3StrideTrap: Boolean = false,
)

@Serializable
data class ComputedBlockLayout(
    val blockName: String,
    val storage: String,
    val packing: String,
    val binding: Int? = null,
    val baseAlignment: Int,
    val fixedByteSize: Int,
    val hasUnsizedTailArray: Boolean,
    val unsizedElementStride: Int? = null,
    val totalImplicitPaddingBytes: Int,
    val members: List<ComputedMemberLayout>,
)

/**
 * Exact Khronos OpenGL 4.6 Section 7.6.2.2 `std140` and `std430` memory layout calculator.
 *
 * Computes per-member byte offsets, base alignments, array strides, column/row-major matrix strides,
 * nested struct alignments, implicit padding holes, and `std430` `vec3` 16-byte stride traps.
 */
object BufferLayoutCalculator {

    private data class ScalarOrVecInfo(val scalarBytes: Int, val components: Int)
    private data class MatrixInfo(val scalarBytes: Int, val cols: Int, val rows: Int)
    private data class TypeFootprint(
        val baseAlignment: Int,
        val size: Int,
        val arrayStride: Int? = null,
        val matrixStride: Int? = null,
        val vec3Trap: Boolean = false,
    )

    fun compute(
        block: BufferBlockDecl,
        structs: Map<String, GlslStruct> = emptyMap(),
        overridePacking: PackingStandard? = null,
    ): ComputedBlockLayout {
        val effectivePacking = overridePacking ?: when (block.packing) {
            PackingStandard.STD430 -> PackingStandard.STD430
            PackingStandard.STD140 -> PackingStandard.STD140
            else -> if (block.storage == BufferStorageKind.BUFFER) PackingStandard.STD430 else PackingStandard.STD140
        }
        val isStd430 = effectivePacking == PackingStandard.STD430

        var cursor = 0
        var maxAlign = if (isStd430) 4 else 16
        var totalPadding = 0
        var hasUnsized = false
        var unsizedStride: Int? = null
        val computedMembers = mutableListOf<ComputedMemberLayout>()

        for (member in block.members) {
            val fp = footprintOf(
                type = member.type,
                arraySize = member.arraySize,
                isUnsizedArray = member.isUnsizedArray,
                rowMajor = member.rowMajor,
                isStd430 = isStd430,
                structs = structs,
                visiting = emptySet(),
            )
            val alignedOffset = roundUp(cursor, fp.baseAlignment)
            val padBefore = alignedOffset - cursor
            totalPadding += padBefore
            if (fp.baseAlignment > maxAlign) maxAlign = fp.baseAlignment

            val elemSize = if (member.isUnsizedArray) 0 else fp.size
            val end = alignedOffset + elemSize
            if (member.isUnsizedArray) {
                hasUnsized = true
                unsizedStride = fp.arrayStride
            }
            val displayType = buildString {
                append(member.type)
                when {
                    member.isUnsizedArray -> append("[]")
                    member.arraySize != null -> append("[${member.arraySize}]")
                    member.arrayExpr != null -> append("[${member.arrayExpr}]")
                }
            }
            computedMembers += ComputedMemberLayout(
                name = member.name,
                type = displayType,
                offset = alignedOffset,
                size = elemSize,
                endOffset = end,
                baseAlignment = fp.baseAlignment,
                paddingBefore = padBefore,
                arraySize = member.arraySize,
                isUnsizedArray = member.isUnsizedArray,
                arrayStride = fp.arrayStride,
                matrixStride = fp.matrixStride,
                vec3StrideTrap = fp.vec3Trap,
            )
            cursor = end
        }

        val blockAlign = if (block.members.isEmpty()) (if (isStd430) 4 else 16) else {
            if (isStd430) maxAlign else roundUp(maxAlign, 16)
        }
        val roundedTotal = if (cursor == 0) 0 else roundUp(cursor, blockAlign)
        if (!hasUnsized && roundedTotal > cursor) {
            totalPadding += (roundedTotal - cursor)
        }

        return ComputedBlockLayout(
            blockName = block.name,
            storage = block.storage.name.lowercase(),
            packing = if (isStd430) "std430" else "std140",
            binding = block.binding,
            baseAlignment = blockAlign,
            fixedByteSize = roundedTotal,
            hasUnsizedTailArray = hasUnsized,
            unsizedElementStride = unsizedStride,
            totalImplicitPaddingBytes = totalPadding,
            members = computedMembers,
        )
    }

    private fun footprintOf(
        type: String,
        arraySize: Int?,
        isUnsizedArray: Boolean,
        rowMajor: Boolean,
        isStd430: Boolean,
        structs: Map<String, GlslStruct>,
        visiting: Set<String>,
    ): TypeFootprint {
        val isArray = isUnsizedArray || arraySize != null
        val count = arraySize ?: 1

        SCALAR_OR_VEC[type]?.let { sv ->
            val elemAlign = when (sv.components) {
                1 -> sv.scalarBytes
                2 -> 2 * sv.scalarBytes
                else -> 4 * sv.scalarBytes // Rule 3 & 4: 3- and 4-component vectors align to 4N
            }
            val elemSize = sv.components * sv.scalarBytes
            if (!isArray) {
                return TypeFootprint(baseAlignment = elemAlign, size = elemSize)
            }
            val stride = if (isStd430) {
                roundUp(elemSize, elemAlign)
            } else {
                roundUp(elemAlign, 16)
            }
            val vec3Trap = isStd430 && sv.components == 3 && sv.scalarBytes == 4
            return TypeFootprint(
                baseAlignment = stride,
                size = if (isUnsizedArray) 0 else count * stride,
                arrayStride = stride,
                vec3Trap = vec3Trap,
            )
        }

        MATRIX_TYPES[type]?.let { mat ->
            val vecCount = if (rowMajor) mat.rows else mat.cols
            val vecComps = if (rowMajor) mat.cols else mat.rows
            val vecAlign = when (vecComps) {
                2 -> 2 * mat.scalarBytes
                else -> 4 * mat.scalarBytes
            }
            val vecSize = vecComps * mat.scalarBytes
            val matStride = if (isStd430) roundUp(vecSize, vecAlign) else roundUp(vecAlign, 16)
            val singleMatrixSize = vecCount * matStride
            val vec3Trap = isStd430 && vecComps == 3 && mat.scalarBytes == 4
            return TypeFootprint(
                baseAlignment = matStride,
                size = if (isUnsizedArray) 0 else count * singleMatrixSize,
                arrayStride = if (isArray) singleMatrixSize else null,
                matrixStride = matStride,
                vec3Trap = vec3Trap,
            )
        }

        val structDef = structs[type]
        if (structDef != null && type !in visiting) {
            var fieldCursor = 0
            var maxFieldAlign = 4
            var lastFieldIsVec3 = false
            for ((idx, f) in structDef.fields.withIndex()) {
                val ffp = footprintOf(
                    type = f.type,
                    arraySize = f.arraySize,
                    isUnsizedArray = f.isUnsizedArray,
                    rowMajor = f.rowMajor || rowMajor,
                    isStd430 = isStd430,
                    structs = structs,
                    visiting = visiting + type,
                )
                val fOffset = roundUp(fieldCursor, ffp.baseAlignment)
                if (ffp.baseAlignment > maxFieldAlign) maxFieldAlign = ffp.baseAlignment
                fieldCursor = fOffset + ffp.size
                if (idx == structDef.fields.lastIndex) {
                    val sv = SCALAR_OR_VEC[f.type]
                    lastFieldIsVec3 = (sv != null && sv.components == 3 && sv.scalarBytes == 4) || ffp.vec3Trap
                }
            }
            val structAlign = if (isStd430) maxFieldAlign else roundUp(maxFieldAlign, 16)
            val structSize = roundUp(fieldCursor.coerceAtLeast(4), structAlign)
            val tailGap = structSize - fieldCursor
            val vec3Trap = isStd430 && lastFieldIsVec3 && tailGap >= 4
            return TypeFootprint(
                baseAlignment = structAlign,
                size = if (isUnsizedArray) 0 else count * structSize,
                arrayStride = if (isArray) structSize else null,
                vec3Trap = vec3Trap,
            )
        }

        // Unknown opaque/user struct fallback: 16-byte aligned slot
        val fallbackStride = 16
        return TypeFootprint(
            baseAlignment = fallbackStride,
            size = if (isUnsizedArray) 0 else count * fallbackStride,
            arrayStride = if (isArray) fallbackStride else null,
        )
    }

    private fun roundUp(value: Int, alignment: Int): Int {
        if (alignment <= 1) return value
        val rem = value % alignment
        return if (rem == 0) value else value + (alignment - rem)
    }

    private val SCALAR_OR_VEC: Map<String, ScalarOrVecInfo> = mapOf(
        "float" to ScalarOrVecInfo(4, 1),
        "int" to ScalarOrVecInfo(4, 1),
        "uint" to ScalarOrVecInfo(4, 1),
        "bool" to ScalarOrVecInfo(4, 1),
        "double" to ScalarOrVecInfo(8, 1),
        "vec2" to ScalarOrVecInfo(4, 2),
        "ivec2" to ScalarOrVecInfo(4, 2),
        "uvec2" to ScalarOrVecInfo(4, 2),
        "bvec2" to ScalarOrVecInfo(4, 2),
        "dvec2" to ScalarOrVecInfo(8, 2),
        "vec3" to ScalarOrVecInfo(4, 3),
        "ivec3" to ScalarOrVecInfo(4, 3),
        "uvec3" to ScalarOrVecInfo(4, 3),
        "bvec3" to ScalarOrVecInfo(4, 3),
        "dvec3" to ScalarOrVecInfo(8, 3),
        "vec4" to ScalarOrVecInfo(4, 4),
        "ivec4" to ScalarOrVecInfo(4, 4),
        "uvec4" to ScalarOrVecInfo(4, 4),
        "bvec4" to ScalarOrVecInfo(4, 4),
        "dvec4" to ScalarOrVecInfo(8, 4),
    )

    private val MATRIX_TYPES: Map<String, MatrixInfo> = mapOf(
        "mat2" to MatrixInfo(4, 2, 2),
        "mat2x2" to MatrixInfo(4, 2, 2),
        "mat2x3" to MatrixInfo(4, 2, 3),
        "mat2x4" to MatrixInfo(4, 2, 4),
        "mat3" to MatrixInfo(4, 3, 3),
        "mat3x2" to MatrixInfo(4, 3, 2),
        "mat3x3" to MatrixInfo(4, 3, 3),
        "mat3x4" to MatrixInfo(4, 3, 4),
        "mat4" to MatrixInfo(4, 4, 4),
        "mat4x2" to MatrixInfo(4, 4, 2),
        "mat4x3" to MatrixInfo(4, 4, 3),
        "mat4x4" to MatrixInfo(4, 4, 4),
        "dmat2" to MatrixInfo(8, 2, 2),
        "dmat3" to MatrixInfo(8, 3, 3),
        "dmat4" to MatrixInfo(8, 4, 4),
    )
}

/**
 * Validates GLSL `std430` and Shader Storage Buffer Object (`buffer`) rules (`SM042`..`SM045`).
 */
object SsboValidator {

    private const val SSBO_EXT = "GL_ARB_shader_storage_buffer_object"

    fun checkFile(
        file: PackFile,
        source: GlslSource,
        versionId: String,
        defaultGlslVersion: Int?,
        transitiveStructs: Map<String, GlslStruct>,
        transitiveExtensions: Set<String>,
        out: MutableList<Finding>,
    ) {
        val effectiveGlsl = source.version?.first ?: defaultGlslVersion ?: 330
        val hasSsboExt = source.hasExtensionEnabled(SSBO_EXT) || SSBO_EXT in transitiveExtensions
        val seenSsboBindings = mutableMapOf<Int, BufferBlockDecl>()
        var reportedMissingSsboExt = false

        for (block in source.bufferBlocks) {
            if (block.storage == BufferStorageKind.UNIFORM) {
                if (block.explicitPacking == "std430") {
                    out += Finding(
                        rule = "SM042",
                        severity = Severity.ERROR,
                        version = versionId,
                        file = file.physicalRelative,
                        line = block.line,
                        message = "Uniform block '${block.name}' declares layout(std430), which OpenGL GLSL forbids on uniform blocks; use layout(std140) for uniform blocks or change 'uniform' to 'buffer' for an SSBO.",
                    )
                }
                block.members.filter { it.isUnsizedArray }.forEach { badMember ->
                    out += Finding(
                        rule = "SM043",
                        severity = Severity.ERROR,
                        version = versionId,
                        file = file.physicalRelative,
                        line = badMember.line,
                        message = "Uniform block '${block.name}' declares unsized array '${badMember.type} ${badMember.name}[]'. Unsized runtime arrays are only permitted as the final member of a shader storage 'buffer' block.",
                    )
                }
                continue
            }

            // Storage == BUFFER (SSBO)
            if (!file.isGlslInclude && effectiveGlsl < 430 && !hasSsboExt && !reportedMissingSsboExt) {
                reportedMissingSsboExt = true
                out += Finding(
                    rule = "SM042",
                    severity = Severity.ERROR,
                    version = versionId,
                    file = file.physicalRelative,
                    line = block.line,
                    message = "Shader storage buffer '${block.name}' requires #version 430+ or '#extension $SSBO_EXT : require' when compiling under #version $effectiveGlsl.",
                )
            }

            if (block.hasInvalidBinding) {
                out += Finding(
                    rule = "SM043",
                    severity = Severity.ERROR,
                    version = versionId,
                    file = file.physicalRelative,
                    line = block.line,
                    message = "Shader storage buffer '${block.name}' has invalid layout binding '${block.rawBindingText ?: ""}' (expected non-negative integer).",
                )
            } else if (block.binding != null) {
                val prev = seenSsboBindings.putIfAbsent(block.binding, block)
                if (prev != null) {
                    out += Finding(
                        rule = "SM043",
                        severity = Severity.ERROR,
                        version = versionId,
                        file = file.physicalRelative,
                        line = block.line,
                        message = "Shader storage buffer '${block.name}' reuses binding = ${block.binding}, already bound by SSBO '${prev.name}' at line ${prev.line}.",
                    )
                }
            }

            // Unsized array must only appear as the very last member of a buffer block
            block.members.forEachIndexed { idx, member ->
                if (member.isUnsizedArray && idx != block.members.lastIndex) {
                    out += Finding(
                        rule = "SM043",
                        severity = Severity.ERROR,
                        version = versionId,
                        file = file.physicalRelative,
                        line = member.line,
                        message = "Unsized runtime array '${member.type} ${member.name}[]' in SSBO '${block.name}' must be the final member of the buffer block; ${block.members.size - 1 - idx} member(s) follow it.",
                    )
                }
            }

            // Memory qualifier access conflicts (SM044)
            checkMemoryAccess(file, source, block, versionId, out)

            // std430 vec3 stride / struct tail padding hazard (SM045)
            if (block.packing == PackingStandard.STD430 || block.explicitPacking == null) {
                val computed = BufferLayoutCalculator.compute(block, transitiveStructs, PackingStandard.STD430)
                computed.members.zip(block.members).forEach { (layoutMem, declMem) ->
                    if (layoutMem.vec3StrideTrap) {
                        val strideInfo = layoutMem.arrayStride?.let { "stride $it bytes (12B data + 4B padding per element)" }
                            ?: "16-byte aligned tail padding"
                        out += Finding(
                            rule = "SM045",
                            severity = Severity.WARNING,
                            version = versionId,
                            file = file.physicalRelative,
                            line = declMem.line,
                            message = "SSBO '${block.name}' member '${layoutMem.type} ${layoutMem.name}' at byte offset ${layoutMem.offset} triggers the std430 vec3 alignment trap ($strideInfo). Host buffers packing 12-byte float3 triples without 16-byte alignment or vec4 padding will read shifted garbage.",
                        )
                    }
                }
            }
        }
    }

    private fun checkMemoryAccess(
        file: PackFile,
        source: GlslSource,
        block: BufferBlockDecl,
        versionId: String,
        out: MutableList<Finding>,
    ) {
        if ("readonly" in block.memoryQualifiers && "writeonly" in block.memoryQualifiers) {
            out += Finding(
                rule = "SM044",
                severity = Severity.ERROR,
                version = versionId,
                file = file.physicalRelative,
                line = block.line,
                message = "SSBO '${block.name}' declares both 'readonly' and 'writeonly' memory qualifiers.",
            )
            return
        }

        val code = source.executableCode
        if (code.isBlank()) return

        for (member in block.members) {
            val effectiveQuals = block.memoryQualifiers + member.memoryQualifiers
            if ("readonly" in effectiveQuals && "writeonly" in effectiveQuals) {
                out += Finding(
                    rule = "SM044",
                    severity = Severity.ERROR,
                    version = versionId,
                    file = file.physicalRelative,
                    line = member.line,
                    message = "SSBO '${block.name}' member '${member.name}' combines 'readonly' and 'writeonly' qualifiers.",
                )
                continue
            }
            val targetExpr = if (block.instanceName != null) {
                "${Regex.escape(block.instanceName)}(?:\\s*\\[[^\\]]*\\])*\\s*\\.\\s*${Regex.escape(member.name)}"
            } else {
                Regex.escape(member.name)
            }

            if ("readonly" in effectiveQuals && isMutatedInCode(code, targetExpr)) {
                out += Finding(
                    rule = "SM044",
                    severity = Severity.ERROR,
                    version = versionId,
                    file = file.physicalRelative,
                    line = block.line,
                    message = "Shader mutates member '${member.name}' of readonly SSBO '${block.name}'. Remove 'readonly' or drop the write/atomic operation.",
                )
            } else if ("writeonly" in effectiveQuals && isReadInCode(code, targetExpr)) {
                out += Finding(
                    rule = "SM044",
                    severity = Severity.ERROR,
                    version = versionId,
                    file = file.physicalRelative,
                    line = block.line,
                    message = "Shader reads member '${member.name}' from writeonly SSBO '${block.name}'. Remove 'writeonly' if the shader reads back from this buffer.",
                )
            }
        }
    }

    private fun isMutatedInCode(code: String, targetPattern: String): Boolean {
        val assignRe = Regex(
            "(?<![A-Za-z0-9_\\.])(?:$targetPattern)(?:\\s*\\[[^\\]]*\\])*(?:\\s*\\.\\s*\\w+(?:\\s*\\[[^\\]]*\\])*)*\\s*(?:[+\\-*/%&|^]|<<|>>)?=(?!=)",
        )
        if (assignRe.containsMatchIn(code)) return true
        val incDecPost = Regex(
            "(?<![A-Za-z0-9_\\.])(?:$targetPattern)(?:\\s*\\[[^\\]]*\\])*(?:\\s*\\.\\s*\\w+(?:\\s*\\[[^\\]]*\\])*)*\\s*(?:\\+\\+|--)",
        )
        if (incDecPost.containsMatchIn(code)) return true
        val incDecPre = Regex("(?:\\+\\+|--)\\s*(?:$targetPattern)\\b")
        if (incDecPre.containsMatchIn(code)) return true
        val atomicRe = Regex("\\batomic(?:Add|Min|Max|And|Or|Xor|Exchange|CompSwap)\\s*\\(\\s*(?:$targetPattern)\\b")
        return atomicRe.containsMatchIn(code)
    }

    private fun isReadInCode(code: String, targetPattern: String): Boolean {
        // Allow `.length()` on writeonly unsized arrays, and strip pure `=` left-hand-side writes.
        val withoutLength = code.replace(Regex("(?:$targetPattern)\\s*\\.\\s*length\\s*\\(\\s*\\)"), "0")
        val pureWriteLhs = Regex(
            "(?<![A-Za-z0-9_\\.])(?:$targetPattern)(?:\\s*\\[[^\\]]*\\])*(?:\\s*\\.\\s*\\w+(?:\\s*\\[[^\\]]*\\])*)*\\s*(?<![+\\-*/%&|^<>!=])=(?!=)",
        )
        // Note: any index expression inside `[idx]` on the LHS could read another variable, so replace only the target access chain up to `=`
        val strippedPureWrites = pureWriteLhs.replace(withoutLength, " __lhs_sink =")
        return Regex("(?<![A-Za-z0-9_\\.])(?:$targetPattern)\\b").containsMatchIn(strippedPureWrites)
    }
}
