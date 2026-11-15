package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.shadersmith.contract.PackMetaReader
import com.salyvn.shadersmith.layout.BufferLayoutCalculator
import com.salyvn.shadersmith.layout.ComputedBlockLayout
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.GlslStruct
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.pack.PackingStandard
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

@Serializable
data class FileBlockLayoutEntry(
    val file: String,
    val line: Int,
    val layout: ComputedBlockLayout,
    val alternateByteSize: Int,
)

/**
 * Computes exact OpenGL `std140` / `std430` byte offsets, alignments, array/matrix strides, and implicit
 * padding holes for uniform and SSBO `buffer` blocks in a GLSL shader or resource pack.
 */
class LayoutCommand : ShadersmithCommand(name = "layout") {

    override fun help(context: Context) =
        "Compute exact std140 and std430 byte offsets, strides, and padding for uniform and SSBO blocks."

    private val target by argument("target", help = "GLSL shader file (.vsh/.fsh/.glsl) or pack directory/zip")
        .path(mustExist = true)

    private val mc by option("--mc", help = "Target Minecraft version for overlay resolution (default: newest known)")

    private val blockFilter by option("--block", help = "Filter by block name")

    private val packingOverride by option("--packing", help = "Force layout rule: std140 or std430")
        .choice("std140", "std430")

    private val json by option("--json", help = "Emit computed layouts as JSON").flag()

    override fun execute() {
        val forced = when (packingOverride) {
            "std140" -> PackingStandard.STD140
            "std430" -> PackingStandard.STD430
            else -> null
        }

        val entries = if (target.isRegularFile() && target.extension.lowercase() != "zip") {
            val src = GlslSource(Files.readString(target, StandardCharsets.UTF_8))
            val structs = src.structs.associateBy { it.name }
            src.bufferBlocks
                .filter { blockFilter == null || it.name == blockFilter }
                .map { blk ->
                    val primary = BufferLayoutCalculator.compute(blk, structs, forced)
                    val altRule = if (primary.packing == "std430") PackingStandard.STD140 else PackingStandard.STD430
                    val alt = BufferLayoutCalculator.compute(blk, structs, altRule)
                    FileBlockLayoutEntry(target.fileName.toString(), blk.line, primary, alt.fixedByteSize)
                }
        } else {
            val contract = dataset.resolve(mc ?: dataset.versions.last().id)
            val pack = PackSource.read(target)
            val overlays = pack.packMeta?.let { PackMetaReader.read(it.text)?.overlays } ?: emptyList()
            val effective = pack.effectiveFiles(contract.packFormat, overlays)
            val glslByPath = effective.filter { it.isShaderSource }.associate { it.relative to GlslSource(it.text) }
            val allStructs = linkedMapOf<String, GlslStruct>()
            glslByPath.values.forEach { g -> g.structs.forEach { s -> allStructs[s.name] = s } }

            effective.filter { it.isShaderSource }.flatMap { file ->
                val src = glslByPath.getValue(file.relative)
                src.bufferBlocks
                    .filter { blockFilter == null || it.name == blockFilter }
                    .map { blk ->
                        val primary = BufferLayoutCalculator.compute(blk, allStructs, forced)
                        val altRule = if (primary.packing == "std430") PackingStandard.STD140 else PackingStandard.STD430
                        val alt = BufferLayoutCalculator.compute(blk, allStructs, altRule)
                        FileBlockLayoutEntry(file.physicalRelative, blk.line, primary, alt.fixedByteSize)
                    }
            }
        }

        if (json) {
            echo(PRETTY.encodeToString(entries))
            return
        }

        if (entries.isEmpty()) {
            echo("No matching uniform or buffer blocks found in $target.")
            return
        }

        entries.forEach { entry ->
            val l = entry.layout
            val bindTag = l.binding?.let { ", binding = $it" } ?: ""
            val altName = if (l.packing == "std430") "std140" else "std430"
            val tailTag = if (l.hasUnsizedTailArray) " + N * ${l.unsizedElementStride}B" else ""
            echo("== ${l.storage} ${l.blockName} (${l.packing}$bindTag)  [${entry.file}:${entry.line}]")
            echo("   size: ${l.fixedByteSize}B$tailTag  (align ${l.baseAlignment}B, padding ${l.totalImplicitPaddingBytes}B; $altName fixed: ${entry.alternateByteSize}B)")
            l.members.forEach { m ->
                val rangeText = if (m.isUnsizedArray) {
                    "[${m.offset}..+${m.arrayStride}B*N)"
                } else {
                    "[${m.offset}..${m.endOffset})"
                }
                val extras = buildList {
                    add("align ${m.baseAlignment}B")
                    if (m.paddingBefore > 0) add("padBefore ${m.paddingBefore}B")
                    m.arrayStride?.let { add("arrayStride ${it}B") }
                    m.matrixStride?.let { add("matrixStride ${it}B") }
                    if (m.vec3StrideTrap) add("WARN: std430 vec3 16B stride trap")
                }.joinToString(", ")
                echo("      ${rangeText.padEnd(16)} ${m.type.padEnd(14)} ${m.name.padEnd(22)} ($extras)")
            }
        }
    }

    companion object {
        private val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
