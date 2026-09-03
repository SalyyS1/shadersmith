package com.salyvn.shadersmith.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One member of a `layout(std140) uniform` block. Order is the contract, so this is a list element. */
@Serializable
data class BlockMember(val type: String, val name: String)

/**
 * What a resource pack's shaders must look like for one Minecraft version.
 *
 * Every field was extracted from the vanilla assets for that version, not hand-written. The interesting
 * ones are the ones that changed without anyone announcing it: which include directive the compiler
 * accepts, whether an include file may carry `#version`, and the exact member order of each uniform
 * block — because std140 is positional, a block with the right members in the wrong order compiles and
 * reads the wrong data.
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

    /** `#include` or `#moj_import`, spelled the way it appears in a source line. */
    val directiveSpelling: String get() = "#${includeDirective ?: "moj_import"}"

    /** Post effect passes name their uniform blocks in a map from 1.21.6; before that, a list. */
    val postUniformsAreBlocks: Boolean get() = postEffectSchema == "uniform-blocks"
}

@Serializable
data class ContractDataset(val generated: String, val source: String, val versions: List<VersionContract>) {
    fun byId(id: String): VersionContract? = versions.firstOrNull { it.id == id }

    fun byPackFormat(format: Int): VersionContract? =
        versions.filter { it.packFormat <= format }.maxByOrNull { it.packFormat }

    /** Resolves `26.2`, `1.21.9` or a bare format number like `88`. */
    fun resolve(text: String): VersionContract {
        byId(text)?.let { return it }
        text.toIntOrNull()?.let { format ->
            byPackFormat(format)?.let { return it }
        }
        throw IllegalArgumentException(
            "Unknown version '$text'. Known: ${versions.joinToString(", ") { it.id }} " +
                "(or a resource pack format number).",
        )
    }

    /** Every version from [from] to [to] inclusive, in release order. */
    fun range(from: VersionContract, to: VersionContract): List<VersionContract> {
        val ordered = versions.sortedWith(compareBy({ it.packFormat }, { it.packFormatMinor }))
        val start = ordered.indexOf(from)
        val end = ordered.indexOf(to)
        require(start <= end) { "${from.id} comes after ${to.id}" }
        return ordered.subList(start, end + 1)
    }

    companion object {
        private val FORMAT = Json { ignoreUnknownKeys = true }

        fun load(): ContractDataset {
            val text = ContractDataset::class.java.getResourceAsStream("/contract-dataset.json")
                ?.bufferedReader()
                ?.readText()
                ?: throw IllegalStateException("contract-dataset.json is missing from the jar")
            return FORMAT.decodeFromString<ContractDataset>(text)
        }
    }
}
