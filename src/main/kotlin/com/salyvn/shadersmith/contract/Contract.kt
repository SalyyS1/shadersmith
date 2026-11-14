package com.salyvn.shadersmith.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One member of a `layout(std140) uniform` block. Order is the contract, so this is a list element. */
@Serializable
data class BlockMember(val type: String, val name: String)

/**
 * What a resource pack's shaders must look like for one Minecraft version.
 *
 * Every field was extracted from the vanilla assets for that version, not hand-written. The critical
 * fields are the ones that changed without raising a compile error: which include directive the compiler
 * accepts, whether an include file may carry `#version`, and the exact member order of each uniform
 * block (because std140 is positional, a block with the right members in the wrong order compiles and
 * reads the wrong data).
 */
@Serializable
data class VersionContract(
    val id: String,
    val packFormat: Int,
    val packFormatMinor: Int = 0,
    val glslVersion: Int? = null,
    val includeDirective: String? = null,
    val includesCarryVersion: Boolean,
    val coreProgramJson: Boolean,
    val postEffectSchema: String,
    val postPassVertexShader: String,
    val coreFiles: List<String>,
    val includeFiles: List<String>,
    val postEffects: List<String>,
    val postPrograms: List<String>,
    val uniformBlocks: Map<String, List<BlockMember>>,
) {
    /** The bare file names a pack may legitimately override under `shaders/core`. */
    val coreProgramNames: Set<String> get() = coreFiles.toSet()

    val includeNames: Set<String> get() = includeFiles.toSet()

    val postProgramNames: Set<String> get() = postPrograms.toSet()

    /** `#include` or `#moj_import`, spelled the way it appears in a source line. */
    val directiveSpelling: String get() = "#${includeDirective ?: "moj_import"}"

    /** Post effect passes name their uniform blocks in a map from 1.21.6; before that, a list. */
    val postUniformsAreBlocks: Boolean get() = postEffectSchema == "uniform-blocks"
}

@Serializable
data class ContractDataset(val generated: String, val source: String, val versions: List<VersionContract>) {
    fun byId(id: String): VersionContract? = versions.firstOrNull { it.id == id }

    fun byPackFormat(format: Int, minor: Int? = null): VersionContract? {
        val candidates = versions.filter {
            it.packFormat < format || (it.packFormat == format && (minor == null || it.packFormatMinor <= minor))
        }
        if (candidates.isEmpty()) return null
        val bestMajor = candidates.maxOf { it.packFormat }
        val inMajor = candidates.filter { it.packFormat == bestMajor }
        val bestMinor = inMajor.maxOf { it.packFormatMinor }
        val matching = inMajor.filter { it.packFormatMinor == bestMinor }
        return matching.firstOrNull { "-" !in it.id } ?: matching.first()
    }

    /** Resolves `26.3`, `1.21.9`, a bare format number like `97`, or a `major.minor` format like `97.1`. */
    fun resolve(text: String): VersionContract {
        val trimmed = text.trim()
        byId(trimmed)?.let { return it }
        trimmed.toIntOrNull()?.let { format ->
            byPackFormat(format)?.let { return it }
        }
        FORMAT_TUPLE.matchEntire(trimmed)?.let { match ->
            val major = match.groupValues[1].toIntOrNull()
            val minor = match.groupValues[2].toIntOrNull()
            if (major != null && minor != null) {
                byPackFormat(major, minor)?.let { return it }
            }
        }
        throw IllegalArgumentException(
            "Unknown version '$text'. Known: ${versions.joinToString(", ") { it.id }} " +
                "(or a resource pack format number).",
        )
    }

    /** Every version from [from] to [to] inclusive, in release order (skipping intermediate pre-releases unless requested). */
    fun range(from: VersionContract, to: VersionContract): List<VersionContract> {
        val ordered = versions
        val start = ordered.indexOf(from)
        val end = ordered.indexOf(to)
        require(start >= 0 && end >= 0 && start <= end) { "${from.id} comes after ${to.id}" }
        val slice = ordered.subList(start, end + 1)
        val includePrereleases = "-" in from.id || "-" in to.id
        return if (includePrereleases) slice else slice.filter { "-" !in it.id || it == from || it == to }
    }

    companion object {
        private val FORMAT = Json { ignoreUnknownKeys = true }
        private val FORMAT_TUPLE = Regex("^(\\d+)\\.(\\d+)$")

        fun load(): ContractDataset {
            val text = ContractDataset::class.java.getResourceAsStream("/contract-dataset.json")
                ?.bufferedReader()
                ?.readText()
                ?: throw IllegalStateException("contract-dataset.json is missing from the jar")
            return FORMAT.decodeFromString<ContractDataset>(text)
        }
    }
}
