package com.salyvn.shadersmith.pipeline

import com.salyvn.shadersmith.contract.VersionContract
import com.salyvn.shadersmith.pack.BufferStorageKind
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.Interpolation
import com.salyvn.shadersmith.pack.IoDirection
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PreprocessorClause
import com.salyvn.shadersmith.pack.StageInterfaceVar
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Severity
import kotlinx.serialization.Serializable

@Serializable
data class PipelineStageBinding(
    val location: Int,
    val slots: Int,
    val type: String,
    val name: String,
    val interpolation: String,
)

@Serializable
data class PipelinePermutationState(
    val name: String,
    val defines: List<String>,
    val vertexInputs: List<PipelineStageBinding>,
    val stageVaryings: List<PipelineStageBinding>,
    val fragmentOutputs: List<PipelineStageBinding>,
    val samplers: List<String>,
    val uniformBlocks: List<String>,
    val storageBuffers: List<String>,
)

@Serializable
data class CompiledPipelineProgram(
    val program: String,
    val namespace: String,
    val version: String,
    val vertexShader: String?,
    val fragmentShader: String?,
    val usesWaveletOit: Boolean,
    val permutations: List<PipelinePermutationState>,
)

/**
 * Compiles and validates Minecraft 26.3+ core shader pipeline states, stage interface locations,
 * integer flat interpolation rules, cross-stage `.vsh` <-> `.fsh` linkage, and Wavelet OIT phase guards
 * (`SM052`..`SM054`).
 */
object PipelineCompiler {

    /** Core fragment shaders in vanilla 26.3 that participate in Wavelet Order-Independent Transparency (`oit.glsl`). */
    val VANILLA_26_3_OIT_FRAGMENT_SHADERS: Set<String> = setOf(
        "block.fsh",
        "clouds.fsh",
        "entity.fsh",
        "item.fsh",
        "particle.fsh",
        "position_color.fsh",
        "rendertype_beacon_beam.fsh",
        "rendertype_crumbling.fsh",
        "rendertype_entity_shadow.fsh",
        "rendertype_lightning.fsh",
        "rendertype_lines.fsh",
        "terrain.fsh",
        "text.fsh",
        "world_border.fsh",
    )

    private val INTEGER_IO_TYPES = setOf(
        "int", "ivec2", "ivec3", "ivec4",
        "uint", "uvec2", "uvec3", "uvec4",
    )

    private val STANDARD_PERMUTATIONS: List<Pair<String, Set<String>>> = listOf(
        "OPAQUE" to emptySet(),
        "ALPHA_CUTOUT" to setOf("ALPHA_CUTOUT"),
        "PER_FACE_LIGHTING" to setOf("PER_FACE_LIGHTING"),
        "EMISSIVE_GLINT" to setOf("EMISSIVE", "GLINT"),
        "OIT_DEPTH_BOUNDS" to setOf("OIT", "OIT_ALPHA_ONLY", "OIT_DEPTH_BOUNDS"),
        "OIT_TRANSMITTANCE" to setOf("OIT", "OIT_ALPHA_ONLY", "OIT_TRANSMITTANCE"),
        "OIT_ACCUMULATE" to setOf("OIT", "OIT_ACCUMULATE"),
    )

    fun checkSingleStage(
        file: PackFile,
        source: GlslSource,
        contract: VersionContract,
        transitiveIncludes: Set<String>,
        out: MutableList<Finding>,
    ) {
        val isFragment = file.fileName.endsWith(".fsh")
        val ioVars = source.interfaceVariables

        // 1. Check location bounds, integer flat qualifier on fragment inputs, and intra-stage location collisions (SM053)
        val explicitVars = ioVars.filter { it.location != null }
        for (v in ioVars) {
            if (isFragment && v.direction == IoDirection.IN && v.type in INTEGER_IO_TYPES && v.interpolation != Interpolation.FLAT) {
                out += Finding(
                    rule = "SM053",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = v.line,
                    message = "Fragment shader integer input '${v.type} ${v.name}' must carry the 'flat' interpolation qualifier; GLSL forbids smooth interpolation of integer varyings.",
                )
            }
            val loc = v.location ?: continue
            val slots = locationSlotsFor(v.type, v.arraySize)
            if (loc < 0 || loc + slots > 16) {
                out += Finding(
                    rule = "SM053",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = v.line,
                    message = "Interface variable '${v.name}' occupies layout location(s) $loc..${loc + slots - 1}, outside the guaranteed [0..15] GLSL stage slot range.",
                )
            }
        }

        for (i in explicitVars.indices) {
            for (j in i + 1 until explicitVars.size) {
                val a = explicitVars[i]
                val b = explicitVars[j]
                if (a.direction != b.direction) continue
                if (GlslSource.areMutuallyExclusive(a.guard, b.guard)) continue
                if (locationsOverlap(a, b)) {
                    out += Finding(
                        rule = "SM053",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = b.line,
                        message = "Duplicate ${a.direction.name.lowercase()} layout(location = ${b.location}) on '${b.name}' collides with '${a.name}' (line ${a.line}) in the same compilation branch.",
                    )
                }
            }
        }

        // 2. Check Minecraft 26.3 Wavelet OIT pipeline state contract (SM054)
        if (isFragment && contract.includeDirective == "include" && "oit.glsl" in contract.includeNames) {
            val includesOit = transitiveIncludes.any {
                it == "minecraft:oit.glsl" ||
                    it.endsWith("/shaders/include/oit.glsl") ||
                    it == "minecraft:oit_depth_bounds.glsl" ||
                    it == "minecraft:oit_add_transmittance.glsl"
            }
            if (includesOit) {
                val oitAlphaDefines = setOf("OIT", "OIT_ALPHA_ONLY", "OIT_DEPTH_BOUNDS")
                val collidingOut = explicitVars.firstOrNull { v ->
                    v.direction == IoDirection.OUT &&
                        v.location == 0 &&
                        isGuardActiveUnderDefines(v.guard, oitAlphaDefines)
                }
                if (collidingOut != null) {
                    out += Finding(
                        rule = "SM054",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = collidingOut.line,
                        message = "Fragment output '${collidingOut.name}' at layout(location = 0) is active during OIT_ALPHA_ONLY passes, colliding with <minecraft:oit.glsl> depth-bounds and transmittance attachments on ${contract.id}. Wrap '${collidingOut.name}' in '#ifndef OIT_ALPHA_ONLY'.",
                    )
                }

                // 3. Wavelet OIT permutation phase attachment conflict & transmittance/depth checks (SM055)
                val oitTransmittanceDefines = setOf("OIT", "OIT_TRANSMITTANCE")
                val transOut = explicitVars.firstOrNull { v ->
                    v.direction == IoDirection.OUT &&
                        v.location == 0 &&
                        isGuardActiveUnderDefines(v.guard, oitTransmittanceDefines)
                }
                if (transOut != null && transOut.type != "vec4" && !transOut.type.startsWith("vec4[")) {
                    out += Finding(
                        rule = "SM055",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = transOut.line,
                        message = "Fragment output '${transOut.name}' at layout(location = 0) has type '${transOut.type}' during OIT_TRANSMITTANCE; Minecraft 26.3 Wavelet OIT transmittance estimation requires vec4[2] or vec4 moment outputs.",
                    )
                }

                if (source.executableCode.contains("gl_FragDepth") &&
                    !transitiveIncludes.any { it.contains("oit_depth_bounds.glsl") } &&
                    !source.executableCode.contains("DEPTH_BOUNDS_ACCURATE")
                ) {
                    out += Finding(
                        rule = "SM055",
                        severity = Severity.WARNING,
                        version = contract.id,
                        file = file.physicalRelative,
                        line = null,
                        message = "Wavelet OIT fragment shader mutates gl_FragDepth without including <minecraft:oit_depth_bounds.glsl> or defining DEPTH_BOUNDS_ACCURATE; custom depth will cause conservative depth-bounds culling to prematurely clip translucent geometry.",
                    )
                }
            } else if (file.namespace == "minecraft" && file.shaderDirectory == "core" && file.fileName in VANILLA_26_3_OIT_FRAGMENT_SHADERS) {
                out += Finding(
                    rule = "SM054",
                    severity = Severity.NOTE,
                    version = contract.id,
                    file = file.physicalRelative,
                    line = null,
                    message = "Overrides vanilla ${contract.id} Wavelet OIT core shader '${file.fileName}' without including <minecraft:oit.glsl>; translucent OIT depth-bounds and transmittance phases will not execute custom logic.",
                )
            }
        }
    }

    /**
     * Cross-checks paired `.vsh` and `.fsh` stage interfaces by `layout(location = N)` (`SM052`).
     */
    fun checkStagePair(
        vshFile: PackFile,
        vshSource: GlslSource,
        fshFile: PackFile,
        fshSource: GlslSource,
        contract: VersionContract,
        out: MutableList<Finding>,
    ) {
        val vshOuts = vshSource.interfaceVariables.filter { it.direction == IoDirection.OUT && it.location != null }
        val fshIns = fshSource.interfaceVariables.filter { it.direction == IoDirection.IN && it.location != null }
        if (fshIns.isEmpty()) return

        // Only enforce location linkage if the vertex shader actually uses explicit locations (or is on 26.3+)
        val vshHasAnyUnqualifiedOut = vshSource.interfaceVariables.any { it.direction == IoDirection.OUT && it.location == null }
        if (vshOuts.isEmpty() && vshHasAnyUnqualifiedOut) return

        for (fIn in fshIns) {
            val loc = fIn.location ?: continue
            val candidates = vshOuts.filter { vOut ->
                val vLoc = vOut.location ?: return@filter false
                val vSlots = locationSlotsFor(vOut.type, vOut.arraySize)
                loc in vLoc until (vLoc + vSlots) && !compareCrossFileMutuallyExclusive(vOut.guard, fIn.guard)
            }
            if (candidates.isEmpty()) {
                // Only flag missing producer if fIn is unconditional OR no vsh output ever touches that location
                val anyBranchCandidate = vshOuts.any { vOut ->
                    val vLoc = vOut.location ?: return@any false
                    loc in vLoc until (vLoc + locationSlotsFor(vOut.type, vOut.arraySize))
                }
                if (!anyBranchCandidate) {
                    out += Finding(
                        rule = "SM052",
                        severity = Severity.ERROR,
                        version = contract.id,
                        file = fshFile.physicalRelative,
                        line = fIn.line,
                        message = "Fragment input 'layout(location = $loc) in ${fIn.type} ${fIn.name}' has no matching 'layout(location = $loc) out' in paired vertex shader '${vshFile.physicalRelative}'.",
                    )
                }
                continue
            }

            val matchingTypeAndInterp = candidates.any { vOut ->
                vOut.type == fIn.type &&
                    vOut.arraySize == fIn.arraySize &&
                    vOut.interpolation == fIn.interpolation
            }
            if (!matchingTypeAndInterp) {
                val sample = candidates.first()
                val vDesc = "${sample.interpolation.name.lowercase()} ${sample.type} ${sample.name}"
                val fDesc = "${fIn.interpolation.name.lowercase()} ${fIn.type} ${fIn.name}"
                out += Finding(
                    rule = "SM052",
                    severity = Severity.ERROR,
                    version = contract.id,
                    file = fshFile.physicalRelative,
                    line = fIn.line,
                    message = "Cross-stage interface mismatch at layout(location = $loc): vertex shader '${vshFile.physicalRelative}' outputs [$vDesc], but fragment shader '${fshFile.physicalRelative}' expects [$fDesc].",
                )
            }
        }
    }

    /**
     * Compiles core shader pipeline state permutations for `shadersmith pipeline`.
     */
    fun compilePrograms(
        effectiveFiles: List<PackFile>,
        glslByPath: Map<String, GlslSource>,
        contract: VersionContract,
        programFilter: String? = null,
    ): List<CompiledPipelineProgram> {
        val coreShaders = effectiveFiles.filter { it.shaderDirectory == "core" && it.isShaderSource }
        val grouped = coreShaders.groupBy { file ->
            val baseName = file.fileName.substringBeforeLast('.')
            "${file.namespace ?: "minecraft"}:$baseName"
        }

        return grouped.entries
            .sortedBy { it.key }
            .mapNotNull { (programKey, files) ->
                val ns = programKey.substringBefore(':')
                val progName = programKey.substringAfter(':')
                if (programFilter != null && programFilter != programKey && programFilter != progName) {
                    return@mapNotNull null
                }
                val vsh = files.firstOrNull { it.fileName.endsWith(".vsh") }
                val fsh = files.firstOrNull { it.fileName.endsWith(".fsh") }
                val vshSrc = vsh?.let { glslByPath[it.relative] }
                val fshSrc = fsh?.let { glslByPath[it.relative] }

                val usesOit = fshSrc?.includes?.any { it.target.endsWith("oit.glsl") } == true
                val relevantMacros = collectReferencedMacros(vshSrc, fshSrc)

                val perms = STANDARD_PERMUTATIONS.filter { (permName, defs) ->
                    permName == "OPAQUE" ||
                        (permName.startsWith("OIT_") && usesOit) ||
                        defs.any { it in relevantMacros }
                }.map { (permName, defs) ->
                    val vIns = vshSrc?.interfaceVariables
                        ?.filter { it.direction == IoDirection.IN && isGuardActiveUnderDefines(it.guard, defs) }
                        ?.map { it.toStageBinding() }
                        ?: emptyList()
                    val varyings = (vshSrc?.interfaceVariables?.filter { it.direction == IoDirection.OUT }
                        ?: fshSrc?.interfaceVariables?.filter { it.direction == IoDirection.IN }
                        ?: emptyList())
                        .filter { isGuardActiveUnderDefines(it.guard, defs) }
                        .map { it.toStageBinding() }
                    val fOuts = buildList {
                        fshSrc?.interfaceVariables
                            ?.filter { it.direction == IoDirection.OUT && isGuardActiveUnderDefines(it.guard, defs) }
                            ?.forEach { add(it.toStageBinding()) }
                        if (usesOit && "OIT_DEPTH_BOUNDS" in defs && none { it.location == 0 }) {
                            add(PipelineStageBinding(0, 1, "vec4", "fragColor (oit_depth_bounds)", "smooth"))
                        } else if (usesOit && "OIT_TRANSMITTANCE" in defs && none { it.location == 0 }) {
                            add(PipelineStageBinding(0, 2, "vec4[2]", "coeff (oit_add_transmittance)", "smooth"))
                        }
                    }
                    val samplers = buildSet {
                        vshSrc?.samplers?.let { addAll(it) }
                        fshSrc?.samplers?.let { addAll(it) }
                        if (usesOit && ("OIT_ACCUMULATE" in defs || "OIT_TRANSMITTANCE" in defs)) {
                            add("DepthBoundsSampler")
                        }
                        if (usesOit && "OIT_ACCUMULATE" in defs) {
                            add("Coeff0")
                            add("Coeff1")
                        }
                    }.sorted()
                    val uBlocks = buildSet {
                        vshSrc?.bufferBlocks?.filter { it.storage == BufferStorageKind.UNIFORM }?.forEach { add(it.name) }
                        fshSrc?.bufferBlocks?.filter { it.storage == BufferStorageKind.UNIFORM }?.forEach { add(it.name) }
                    }.sorted()
                    val ssbos = buildSet {
                        vshSrc?.bufferBlocks?.filter { it.storage == BufferStorageKind.BUFFER }?.forEach {
                            add(if (it.binding != null) "${it.name}@${it.binding}" else it.name)
                        }
                        fshSrc?.bufferBlocks?.filter { it.storage == BufferStorageKind.BUFFER }?.forEach {
                            add(if (it.binding != null) "${it.name}@${it.binding}" else it.name)
                        }
                    }.sorted()

                    PipelinePermutationState(
                        name = permName,
                        defines = defs.sorted(),
                        vertexInputs = vIns,
                        stageVaryings = varyings,
                        fragmentOutputs = fOuts,
                        samplers = samplers,
                        uniformBlocks = uBlocks,
                        storageBuffers = ssbos,
                    )
                }

                CompiledPipelineProgram(
                    program = progName,
                    namespace = ns,
                    version = contract.id,
                    vertexShader = vsh?.physicalRelative,
                    fragmentShader = fsh?.physicalRelative,
                    usesWaveletOit = usesOit,
                    permutations = perms,
                )
            }
    }

    private fun StageInterfaceVar.toStageBinding(): PipelineStageBinding = PipelineStageBinding(
        location = location ?: -1,
        slots = locationSlotsFor(type, arraySize),
        type = if (arraySize != null) "$type[$arraySize]" else if (arrayExpr != null) "$type[$arrayExpr]" else type,
        name = name,
        interpolation = interpolation.name.lowercase(),
    )

    private fun collectReferencedMacros(vararg sources: GlslSource?): Set<String> {
        val macros = mutableSetOf<String>()
        val identRe = Regex("\\b([A-Z][A-Z0-9_]{2,})\\b")
        sources.filterNotNull().forEach { src ->
            src.interfaceVariables.forEach { v ->
                v.guard.forEach { g ->
                    g.macroSign?.let { macros += it.first }
                    identRe.findAll(g.rawCondition).forEach { macros += it.groupValues[1] }
                }
            }
        }
        return macros
    }

    fun locationSlotsFor(type: String, arraySize: Int?): Int {
        val baseSlots = when (type) {
            "mat2", "mat2x2", "mat2x3", "mat2x4", "dmat2" -> 2
            "mat3", "mat3x2", "mat3x3", "mat3x4" -> 3
            "mat4", "mat4x2", "mat4x3", "mat4x4" -> 4
            "dvec3", "dvec4" -> 2
            else -> 1
        }
        return baseSlots * (arraySize ?: 1).coerceAtLeast(1)
    }

    private fun componentSpanFor(type: String): Int = when {
        type in setOf("float", "int", "uint", "bool") -> 1
        type.endsWith("2") && !type.startsWith("mat") -> 2
        type.endsWith("3") && !type.startsWith("mat") -> 3
        else -> 4
    }

    private fun locationsOverlap(a: StageInterfaceVar, b: StageInterfaceVar): Boolean {
        val locA = a.location ?: return false
        val locB = b.location ?: return false
        val endA = locA + locationSlotsFor(a.type, a.arraySize)
        val endB = locB + locationSlotsFor(b.type, b.arraySize)
        if (locA >= endB || locB >= endA) return false
        // If both occupy the exact same single location and carry explicit `component = C`, check component overlap
        if (a.component != null && b.component != null && locA == locB) {
            val compEndA = a.component + componentSpanFor(a.type)
            val compEndB = b.component + componentSpanFor(b.type)
            return a.component < compEndB && b.component < compEndA
        }
        return true
    }

    private fun compareCrossFileMutuallyExclusive(
        a: List<PreprocessorClause>,
        b: List<PreprocessorClause>,
    ): Boolean {
        for (ca in a) {
            val sa = ca.macroSign ?: continue
            for (cb in b) {
                val sb = cb.macroSign ?: continue
                if (sa.first == sb.first && sa.second != sb.second) return true
            }
        }
        return false
    }

    internal fun isGuardActiveUnderDefines(guard: List<PreprocessorClause>, defines: Set<String>): Boolean {
        for (clause in guard) {
            val sign = clause.macroSign
            if (sign != null) {
                val isDefined = sign.first in defines
                if (sign.second != isDefined) return false
                continue
            }
            // Fallback evaluation for compound expressions like `defined(OIT_TRANSMITTANCE) || defined(OIT_ACCUMULATE)`
            val raw = clause.rawCondition
            if (raw.contains("defined")) {
                val evaluated = evaluateDefinedExpr(raw, defines)
                if (evaluated != null && !evaluated) return false
            }
        }
        return true
    }

    private fun evaluateDefinedExpr(expr: String, defines: Set<String>): Boolean? {
        if (expr.startsWith("else(") && expr.endsWith(")")) {
            val inner = evaluateDefinedExpr(expr.removePrefix("else(").removeSuffix(")"), defines) ?: return null
            return !inner
        }
        if ("||" in expr) {
            val parts = expr.split("||").map { evaluateDefinedExpr(it.trim(), defines) ?: return null }
            return parts.any { it }
        }
        if ("&&" in expr) {
            val parts = expr.split("&&").map { evaluateDefinedExpr(it.trim(), defines) ?: return null }
            return parts.all { it }
        }
        val clean = expr.trim().removeSurrounding("(", ")").trim()
        Regex("^!\\s*defined\\s*\\(?\\s*([A-Za-z_]\\w*)\\s*\\)?$").matchEntire(clean)?.let {
            return it.groupValues[1] !in defines
        }
        Regex("^defined\\s*\\(?\\s*([A-Za-z_]\\w*)\\s*\\)?$").matchEntire(clean)?.let {
            return it.groupValues[1] in defines
        }
        return null
    }
}
