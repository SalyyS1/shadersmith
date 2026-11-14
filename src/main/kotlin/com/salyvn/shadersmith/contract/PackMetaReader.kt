package com.salyvn.shadersmith.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** One overlay directory entry declared in `pack.mcmeta`'s `overlays.entries`. */
data class OverlayEntry(
    val directory: String,
    val min: Int,
    val max: Int,
) {
    fun matches(packFormat: Int): Boolean = packFormat in min..max
}

/** The pack format range and overlays a `pack.mcmeta` claims, read leniently across both format eras. */
data class DeclaredRange(
    val min: Int?,
    val max: Int?,
    val packFormat: Int?,
    val supportedFormats: Pair<Int, Int>?,
    val overlays: List<OverlayEntry> = emptyList(),
) {
    /** The lowest and highest major formats the pack claims, from whichever fields are present. */
    val effectiveMin: Int? get() = min ?: supportedFormats?.first ?: packFormat
    val effectiveMax: Int? get() = max ?: supportedFormats?.second ?: packFormat
}

object PackMetaReader {
    private val FORMAT = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(text: String): DeclaredRange? {
        val root = try {
            FORMAT.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return null
        }
        val pack = (root["pack"] as? JsonObject) ?: return null
        val overlays = parseOverlays(root["overlays"] as? JsonObject)
        return DeclaredRange(
            min = major(pack["min_format"]),
            max = major(pack["max_format"]),
            packFormat = (pack["pack_format"] as? JsonPrimitive)?.intOrNull,
            supportedFormats = parseFormatsRange(pack["supported_formats"]),
            overlays = overlays,
        )
    }

    private fun parseOverlays(overlaysObj: JsonObject?): List<OverlayEntry> {
        val entries = overlaysObj?.get("entries") as? JsonArray ?: return emptyList()
        return entries.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val dir = (obj["directory"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim('/')
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val minFmt = major(obj["min_format"])
            val maxFmt = major(obj["max_format"])
            val range = if (minFmt != null && maxFmt != null) {
                minFmt to maxFmt
            } else {
                parseFormatsRange(obj["formats"])
                    ?: major(obj["formats"])?.let { it to it }
            } ?: return@mapNotNull null
            OverlayEntry(dir, range.first, range.second)
        }
    }

    private fun parseFormatsRange(element: JsonElement?): Pair<Int, Int>? = when (element) {
        is JsonArray -> {
            val lo = (element.getOrNull(0) as? JsonPrimitive)?.intOrNull
            val hi = (element.getOrNull(1) as? JsonPrimitive)?.intOrNull
            if (lo != null && hi != null) lo to hi else null
        }
        is JsonObject -> {
            val lo = (element["min_inclusive"] as? JsonPrimitive)?.intOrNull
            val hi = (element["max_inclusive"] as? JsonPrimitive)?.intOrNull
            if (lo != null && hi != null) lo to hi else null
        }
        else -> null
    }

    /** `88` or `[97, 1]` -> the major. */
    private fun major(element: JsonElement?): Int? = when (element) {
        is JsonPrimitive -> element.intOrNull
        is JsonArray -> (element.firstOrNull() as? JsonPrimitive)?.intOrNull
        else -> null
    }
}
