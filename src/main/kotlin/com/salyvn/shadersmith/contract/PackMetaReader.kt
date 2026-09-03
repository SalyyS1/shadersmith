package com.salyvn.shadersmith.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** The pack format range a `pack.mcmeta` claims, read leniently: either era's fields, whichever exist. */
data class DeclaredRange(val min: Int?, val max: Int?, val packFormat: Int?, val supportedFormats: Pair<Int, Int>?) {
    /** The lowest and highest major formats the pack claims, from whichever fields are present. */
    val effectiveMin: Int? get() = min ?: supportedFormats?.first ?: packFormat
    val effectiveMax: Int? get() = max ?: supportedFormats?.second ?: packFormat
}

object PackMetaReader {
    private val FORMAT = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(text: String): DeclaredRange? {
        val root = try {
            FORMAT.parseToJsonElement(text).jsonObject
        } catch (failure: Exception) {
            return null
        }
        val pack = (root["pack"] as? JsonObject) ?: return null
        return DeclaredRange(
            min = major(pack["min_format"]),
            max = major(pack["max_format"]),
            packFormat = (pack["pack_format"] as? JsonPrimitive)?.intOrNull,
            supportedFormats = (pack["supported_formats"] as? JsonArray)?.let { array ->
                val lo = (array.getOrNull(0) as? JsonPrimitive)?.intOrNull
                val hi = (array.getOrNull(1) as? JsonPrimitive)?.intOrNull
                if (lo != null && hi != null) lo to hi else null
            } ?: (pack["supported_formats"] as? JsonObject)?.let { obj ->
                val lo = (obj["min_inclusive"] as? JsonPrimitive)?.intOrNull
                val hi = (obj["max_inclusive"] as? JsonPrimitive)?.intOrNull
                if (lo != null && hi != null) lo to hi else null
            },
        )
    }

    /** `88` or `[97, 1]` -> the major. */
    private fun major(element: kotlinx.serialization.json.JsonElement?): Int? = when (element) {
        is JsonPrimitive -> element.intOrNull
        is JsonArray -> (element.firstOrNull() as? JsonPrimitive)?.intOrNull
        else -> null
    }
}
