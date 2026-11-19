package com.salyvn.shadersmith.vram

import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Severity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.max
import kotlin.math.min

@Serializable
data class TargetVramAllocation(
    val name: String,
    val format: String,
    val bytesPerPixel: Int,
    val width: Int,
    val height: Int,
    val viewportScaled: Boolean,
    val persistent: Boolean,
    val hasClearColor: Boolean,
    val allocatedBytes: Long,
    val allocated4kBytes: Long,
    val firstWritePass: Int?,
    val lastReadPass: Int?,
    val writeCount: Int,
    val readCount: Int,
    val aliasSlot: Int,
)

@Serializable
data class AliasingPoolSlot(
    val slot: Int,
    val format: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val targets: List<String>,
)

@Serializable
data class VramFindingDto(
    val rule: String,
    val severity: String,
    val version: String,
    val file: String,
    val message: String,
)

@Serializable
data class PostEffectVramReport(
    val file: String,
    val passCount: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val fps: Int,
    val budgetBytes: Long,
    val allocatedVramBytes: Long,
    val allocated4kVramBytes: Long,
    val aliasedPeakVramBytes: Long,
    val reclaimableAliasBytes: Long,
    val bytesReadPerFrame: Long,
    val bytesWrittenPerFrame: Long,
    val estimatedBandwidthGbPerSec: Double,
    val writesExternalOrPersistentOutput: Boolean,
    val targets: List<TargetVramAllocation>,
    val aliasPools: List<AliasingPoolSlot>,
    val findings: List<VramFindingDto>,
)

/**
 * Computes exact per-target VRAM footprints, 4K scaling projections, frame read/write memory bandwidth,
 * transient render-target live-interval aliasing pools, and multi-pass ping-pong / dead-end chain hazards
 * (`SM080`, `SM081`, `SM082`) for Minecraft `post_effect/<name>.json` pipelines.
 */
object VramCalculator {

    const val DEFAULT_WIDTH: Int = 1920
    const val DEFAULT_HEIGHT: Int = 1080
    const val DEFAULT_FPS: Int = 60
    const val DEFAULT_BUDGET_MB: Double = 128.0

    private val STRICT_JSON = Json { ignoreUnknownKeys = true }

    private val BYTES_PER_PIXEL: Map<String, Int> = mapOf(
        "R8" to 1, "R8I" to 1, "R8UI" to 1,
        "RG8" to 2, "RG8I" to 2, "RG8UI" to 2,
        "R16" to 2, "R16F" to 2, "R16I" to 2, "R16UI" to 2,
        "DEPTH_COMPONENT16" to 2,
        "RGB8" to 3, "DEPTH_COMPONENT24" to 3,
        "RGBA8" to 4, "SRGB8_ALPHA8" to 4, "RGBA8I" to 4, "RGBA8UI" to 4,
        "RGB10_A2" to 4, "R11F_G11F_B10F" to 4,
        "RG16" to 4, "RG16F" to 4, "RG16I" to 4, "RG16UI" to 4,
        "R32F" to 4, "R32I" to 4, "R32UI" to 4,
        "DEPTH_COMPONENT" to 4, "DEPTH24_STENCIL8" to 4, "DEPTH_COMPONENT32F" to 4, "DEPTH32F" to 4,
        "RGBA16" to 8, "RGBA16F" to 8, "RGBA16I" to 8, "RGBA16UI" to 8,
        "RG32F" to 8, "RG32I" to 8, "RG32UI" to 8,
        "DEPTH32F_STENCIL8" to 8,
        "RGBA32F" to 16, "RGBA32I" to 16, "RGBA32UI" to 16,
    )

    fun bytesPerPixelFor(format: String): Int =
        BYTES_PER_PIXEL[format.trim().uppercase()] ?: 4

    fun analyzePostEffect(
        file: PackFile,
        versionId: String,
        viewportWidth: Int = DEFAULT_WIDTH,
        viewportHeight: Int = DEFAULT_HEIGHT,
        fps: Int = DEFAULT_FPS,
        budgetMb: Double = DEFAULT_BUDGET_MB,
        out: MutableList<Finding>? = null,
    ): PostEffectVramReport? {
        val root = try {
            STRICT_JSON.parseToJsonElement(file.text).jsonObject
        } catch (_: Exception) {
            return null
        }

        val targetsObj = root["targets"] as? JsonObject
        val passesArr = root["passes"] as? JsonArray ?: JsonArray(emptyList())
        val externalTargets = ((root["external_targets"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.toSet() ?: emptySet())

        data class MutableTargetState(
            val name: String,
            val format: String,
            val bpp: Int,
            val width: Int,
            val height: Int,
            val viewportScaled: Boolean,
            val persistent: Boolean,
            val hasClearColor: Boolean,
            val bytes: Long,
            val bytes4k: Long,
            var firstWritePass: Int? = null,
            var lastWritePass: Int? = null,
            var firstReadPass: Int? = null,
            var lastReadPass: Int? = null,
            var writeCount: Int = 0,
            var readCount: Int = 0,
            var pendingUnconsumedWritePass: Int? = null,
            var aliasSlot: Int = -1,
        )

        val states = linkedMapOf<String, MutableTargetState>()
        targetsObj?.forEach { (name, elem) ->
            val cfg = elem as? JsonObject ?: JsonObject(emptyMap())
            val rawW = (cfg["width"] as? JsonPrimitive)?.intOrNull
            val rawH = (cfg["height"] as? JsonPrimitive)?.intOrNull
            val fixedDims = rawW != null && rawH != null && rawW > 0 && rawH > 0
            val w = if (fixedDims) rawW else viewportWidth
            val h = if (fixedDims) rawH else viewportHeight
            val w4k = if (fixedDims) rawW else 3840
            val h4k = if (fixedDims) rawH else 2160
            val fmt = ((cfg["format"] ?: cfg["pixel_format"]) as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim()?.uppercase() ?: "RGBA8"
            val bpp = bytesPerPixelFor(fmt)
            val persistent = (cfg["persistent"] as? JsonPrimitive)?.booleanOrNull ?: false
            val hasClear = "clear_color" in cfg
            val bytes = w.toLong() * h.toLong() * bpp.toLong()
            val bytes4k = w4k.toLong() * h4k.toLong() * bpp.toLong()
            states[name] = MutableTargetState(
                name = name,
                format = fmt,
                bpp = bpp,
                width = w,
                height = h,
                viewportScaled = !fixedDims,
                persistent = persistent,
                hasClearColor = hasClear,
                bytes = bytes,
                bytes4k = bytes4k,
            )
        }

        val localFindings = mutableListOf<Finding>()
        val mainColorBytes = viewportWidth.toLong() * viewportHeight.toLong() * 4L
        val mainDepthBytes = viewportWidth.toLong() * viewportHeight.toLong() * 4L
        var bytesRead = 0L
        var bytesWritten = 0L
        var wroteAnyInternal = false
        var wroteExternalOrPersistent = false

        fun isExternal(target: String): Boolean = target.contains(':') || target in externalTargets

        passesArr.forEachIndexed { passIdx, passElem ->
            val pass = passElem as? JsonObject ?: return@forEachIndexed
            val passNum = passIdx + 1
            val inputs = pass["inputs"] as? JsonArray
            val output = (pass["output"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val hasBlend = "blend" in pass || ((pass["use_linear_filter"] as? JsonPrimitive)?.booleanOrNull == true && false)

            val sampledInThisPass = mutableSetOf<String>()
            inputs?.forEach { inElem ->
                val inObj = inElem as? JsonObject ?: return@forEach
                val inTarget = (inObj["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val useDepth = (inObj["use_depth_buffer"] as? JsonPrimitive)?.booleanOrNull ?: false
                if (inTarget != null) {
                    sampledInThisPass += inTarget
                    if (isExternal(inTarget)) {
                        bytesRead += if (useDepth) mainDepthBytes else mainColorBytes
                    } else {
                        val st = states[inTarget]
                        if (st != null) {
                            bytesRead += st.bytes
                            st.readCount++
                            if (st.firstReadPass == null) st.firstReadPass = passNum
                            st.lastReadPass = passNum
                            st.pendingUnconsumedWritePass = null
                            if (st.persistent && st.writeCount == 0 && !st.hasClearColor && st.readCount == 1) {
                                localFindings += Finding(
                                    rule = "SM081",
                                    severity = Severity.WARNING,
                                    version = versionId,
                                    file = file.physicalRelative,
                                    line = null,
                                    message = "Pass #$passNum samples persistent temporal target '$inTarget' before any pass writes to it, and '$inTarget' declares no 'clear_color'; frame 0 will sample uninitialized VRAM.",
                                )
                            }
                        }
                    }
                } else {
                    val w = (inObj["width"] as? JsonPrimitive)?.intOrNull ?: 0
                    val h = (inObj["height"] as? JsonPrimitive)?.intOrNull ?: 0
                    if (w > 0 && h > 0) bytesRead += w.toLong() * h.toLong() * 4L
                }
            }

            if (output != null) {
                if (isExternal(output)) {
                    bytesWritten += mainColorBytes
                    wroteExternalOrPersistent = true
                } else {
                    val st = states[output]
                    if (st != null) {
                        wroteAnyInternal = true
                        if (st.persistent) wroteExternalOrPersistent = true
                        bytesWritten += st.bytes
                        val prevUnconsumed = st.pendingUnconsumedWritePass
                        if (prevUnconsumed != null && output !in sampledInThisPass && !hasBlend) {
                            localFindings += Finding(
                                rule = "SM080",
                                severity = Severity.WARNING,
                                version = versionId,
                                file = file.physicalRelative,
                                line = null,
                                message = "Pass #$passNum overwrites internal render target '$output' before its output from Pass #$prevUnconsumed is ever sampled (broken ping-pong swap or dead render pass).",
                            )
                        }
                        if (st.firstWritePass == null) st.firstWritePass = passNum
                        st.lastWritePass = passNum
                        st.writeCount++
                        st.pendingUnconsumedWritePass = passNum
                    }
                }
            }
        }

        if (wroteAnyInternal && !wroteExternalOrPersistent && passesArr.isNotEmpty()) {
            localFindings += Finding(
                rule = "SM081",
                severity = Severity.WARNING,
                version = versionId,
                file = file.physicalRelative,
                line = null,
                message = "Post-effect chain writes to transient internal render target(s) across ${passesArr.size} pass(es) without ever writing back to 'minecraft:main' (or an external/persistent target); all rendered pixels are discarded.",
            )
        }

        // Compute linear-scan render-target aliasing across non-overlapping transient lifetimes with identical (w, h, format).
        data class PoolTrack(
            val slot: Int,
            val format: String,
            val width: Int,
            val height: Int,
            val bytes: Long,
            var busyUntilPass: Int,
            val members: MutableList<String>,
        )

        val pools = mutableListOf<PoolTrack>()
        val totalPasses = max(passesArr.size, 1)

        val orderedStates = states.values.sortedWith(
            compareBy<MutableTargetState> {
                if (it.persistent) 0 else min(it.firstWritePass ?: (totalPasses + 1), it.firstReadPass ?: (totalPasses + 1))
            }.thenBy { it.name },
        )

        for (st in orderedStates) {
            if (st.persistent) {
                val slotId = pools.size
                st.aliasSlot = slotId
                pools += PoolTrack(slotId, st.format, st.width, st.height, st.bytes, Int.MAX_VALUE, mutableListOf(st.name))
                continue
            }
            val startPass = min(st.firstWritePass ?: 0, st.firstReadPass ?: Int.MAX_VALUE)
            val endPass = max(st.lastReadPass ?: 0, st.lastWritePass ?: 0)
            if (st.writeCount == 0 && st.readCount == 0) {
                // Dead target: needs 0 runtime slots if pruned, assign standalone slot for reporting clarity
                val slotId = pools.size
                st.aliasSlot = slotId
                pools += PoolTrack(slotId, st.format, st.width, st.height, 0L, -1, mutableListOf(st.name))
                continue
            }
            val reusable = pools.firstOrNull { p ->
                p.bytes > 0L &&
                    p.busyUntilPass < Int.MAX_VALUE &&
                    p.format == st.format &&
                    p.width == st.width &&
                    p.height == st.height &&
                    p.busyUntilPass < startPass
            }
            if (reusable != null) {
                st.aliasSlot = reusable.slot
                reusable.busyUntilPass = endPass
                reusable.members += st.name
            } else {
                val slotId = pools.size
                st.aliasSlot = slotId
                pools += PoolTrack(slotId, st.format, st.width, st.height, st.bytes, endPass, mutableListOf(st.name))
            }
        }

        val allocatedBytes = states.values.sumOf { it.bytes }
        val allocated4kBytes = states.values.sumOf { it.bytes4k }
        val aliasedPeakBytes = pools.sumOf { it.bytes }
        val reclaimableBytes = (allocatedBytes - aliasedPeakBytes).coerceAtLeast(0L)
        val budgetBytes = (budgetMb * 1024.0 * 1024.0).toLong()

        if (allocatedBytes > budgetBytes) {
            val allocMb = String.format(java.util.Locale.ROOT, "%.2f", allocatedBytes / (1024.0 * 1024.0))
            val peakMb = String.format(java.util.Locale.ROOT, "%.2f", aliasedPeakBytes / (1024.0 * 1024.0))
            val budgetStr = String.format(java.util.Locale.ROOT, "%.2f", budgetMb)
            localFindings += Finding(
                rule = "SM082",
                severity = Severity.WARNING,
                version = versionId,
                file = file.physicalRelative,
                line = null,
                message = "Post-effect allocates $allocMb MiB across ${states.size} render target(s) at ${viewportWidth}x${viewportHeight}, exceeding the $budgetStr MiB VRAM budget (live-range aliased minimum: $peakMb MiB).",
            )
        }

        out?.addAll(localFindings)

        val bwGbPerSec = ((bytesRead + bytesWritten).toDouble() * fps.toDouble()) / 1_000_000_000.0
        val roundedBw = kotlin.math.round(bwGbPerSec * 1000.0) / 1000.0

        return PostEffectVramReport(
            file = file.physicalRelative,
            passCount = passesArr.size,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            fps = fps,
            budgetBytes = budgetBytes,
            allocatedVramBytes = allocatedBytes,
            allocated4kVramBytes = allocated4kBytes,
            aliasedPeakVramBytes = aliasedPeakBytes,
            reclaimableAliasBytes = reclaimableBytes,
            bytesReadPerFrame = bytesRead,
            bytesWrittenPerFrame = bytesWritten,
            estimatedBandwidthGbPerSec = roundedBw,
            writesExternalOrPersistentOutput = wroteExternalOrPersistent,
            targets = states.values.map { st ->
                TargetVramAllocation(
                    name = st.name,
                    format = st.format,
                    bytesPerPixel = st.bpp,
                    width = st.width,
                    height = st.height,
                    viewportScaled = st.viewportScaled,
                    persistent = st.persistent,
                    hasClearColor = st.hasClearColor,
                    allocatedBytes = st.bytes,
                    allocated4kBytes = st.bytes4k,
                    firstWritePass = st.firstWritePass,
                    lastReadPass = st.lastReadPass,
                    writeCount = st.writeCount,
                    readCount = st.readCount,
                    aliasSlot = st.aliasSlot,
                )
            },
            aliasPools = pools.filter { it.bytes > 0L }.map { p ->
                AliasingPoolSlot(
                    slot = p.slot,
                    format = p.format,
                    width = p.width,
                    height = p.height,
                    bytes = p.bytes,
                    targets = p.members.toList(),
                )
            },
            findings = localFindings.map {
                VramFindingDto(
                    rule = it.rule,
                    severity = it.severity.label,
                    version = it.version,
                    file = it.file,
                    message = it.message,
                )
            },
        )
    }
}
