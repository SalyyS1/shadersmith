package com.salyvn.shadersmith.spirv

import com.salyvn.shadersmith.pack.BufferStorageKind
import com.salyvn.shadersmith.pack.GlslSource
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Severity
import kotlinx.serialization.Serializable

@Serializable
data class SpirvBindingDescriptor(
    val set: Int,
    val binding: Int,
    val kind: String,
    val name: String,
    val type: String,
    val stage: String,
)

@Serializable
data class SpirvInterfaceLocation(
    val location: Int,
    val direction: String,
    val name: String,
    val type: String,
    val interpolation: String,
)

@Serializable
data class SpirvFinding(
    val rule: String,
    val severity: String,
    val version: String,
    val file: String,
    val line: Int?,
    val message: String,
)

@Serializable
data class SpirvAuditReport(
    val file: String,
    val isSpirvCompliant: Boolean,
    val bindings: List<SpirvBindingDescriptor>,
    val interfaces: List<SpirvInterfaceLocation>,
    val findings: List<SpirvFinding>,
)

/**
 * Validates GLSL against modern Vulkan / Metal / ARB_gl_spirv SPIR-V intermediate representation
 * cross-compilation contracts (`SM057` and `SM058`).
 */
object SpirvValidator {

    private val LEGACY_BUILTIN_FIXES = mapOf(
        "gl_FragColor" to "declare 'layout(location = 0) out vec4 outColor;' instead of assigning to gl_FragColor",
        "gl_FragData" to "declare explicit 'layout(location = N) out' stage outputs instead of indexing gl_FragData",
        "attribute" to "replace deprecated 'attribute' keyword with 'in'",
        "varying" to "replace deprecated 'varying' keyword with 'in' or 'out'",
        "gl_Normal" to "declare explicit 'layout(location = N) in vec3 inNormal;' vertex input",
        "gl_Vertex" to "declare explicit 'layout(location = N) in vec4 inPosition;' vertex input",
        "gl_MultiTexCoord0" to "declare explicit 'layout(location = N) in vec2 inTexCoord0;' vertex input",
        "gl_ModelViewMatrix" to "pass matrix via std140 uniform buffer instead of legacy gl_ModelViewMatrix",
        "gl_ProjectionMatrix" to "pass matrix via std140 uniform buffer instead of legacy gl_ProjectionMatrix",
        "gl_ModelViewProjectionMatrix" to "pass MVP matrix via uniform block instead of gl_ModelViewProjectionMatrix",
        "gl_NormalMatrix" to "compute normal matrix in vertex shader or pass via uniform block instead of gl_NormalMatrix",
    )

    fun checkFile(
        file: PackFile,
        source: GlslSource,
        versionId: String,
        out: MutableList<Finding>,
    ) {
        val stageName = when {
            file.fileName.endsWith(".vsh") -> "vertex"
            file.fileName.endsWith(".fsh") -> "fragment"
            file.fileName.endsWith(".csh") -> "compute"
            else -> "shader"
        }

        // 1. Dialect violations (SM057)
        for ((builtin, line) in source.legacyBuiltinUsages) {
            val fixHint = LEGACY_BUILTIN_FIXES[builtin]?.let { " ($it)" } ?: ""
            out += Finding(
                rule = "SM057",
                severity = Severity.ERROR,
                version = versionId,
                file = file.physicalRelative,
                line = line,
                message = "Legacy GLSL built-in '$builtin' is forbidden when targeting SPIR-V intermediate representation$fixHint.",
            )
        }

        for ((decl, line) in source.nakedUniforms) {
            out += Finding(
                rule = "SM057",
                severity = Severity.ERROR,
                version = versionId,
                file = file.physicalRelative,
                line = line,
                message = "Naked uniform '$decl' at file scope is forbidden targeting SPIR-V. Vulkan SPIR-V requires all uniforms to reside inside a uniform block with explicit layout(binding = N) or push_constant block.",
            )
        }

        // 2. Interface location and descriptor binding requirements (SM058)
        if (!file.isGlslInclude) {
            for ((declaration, line) in source.unqualifiedInterfaceVariables) {
                out += Finding(
                    rule = "SM058",
                    severity = Severity.ERROR,
                    version = versionId,
                    file = file.physicalRelative,
                    line = line,
                    message = "Stage interface variable '$declaration' lacks explicit 'layout(location = N)' required by SPIR-V stage interface linkage.",
                )
            }

            for (block in source.bufferBlocks) {
                if (block.binding == null) {
                    val kind = if (block.storage == BufferStorageKind.BUFFER) "Storage buffer (SSBO)" else "Uniform block"
                    out += Finding(
                        rule = "SM058",
                        severity = Severity.ERROR,
                        version = versionId,
                        file = file.physicalRelative,
                        line = block.line,
                        message = "$kind '${block.name}' lacks explicit 'layout(binding = N)' required for SPIR-V descriptor set assignment.",
                    )
                }
            }

            for (sampler in source.declaredSamplers) {
                if (sampler.binding == null) {
                    out += Finding(
                        rule = "SM058",
                        severity = Severity.ERROR,
                        version = versionId,
                        file = file.physicalRelative,
                        line = sampler.line,
                        message = "Sampler uniform '${sampler.name}' lacks explicit 'layout(binding = N)' required for SPIR-V descriptor set assignment.",
                    )
                }
            }
        }
    }

    fun auditFile(file: PackFile, source: GlslSource, versionId: String): SpirvAuditReport {
        val findings = mutableListOf<Finding>()
        checkFile(file, source, versionId, findings)

        val stage = when {
            file.fileName.endsWith(".vsh") -> "vertex"
            file.fileName.endsWith(".fsh") -> "fragment"
            file.fileName.endsWith(".csh") -> "compute"
            else -> "common"
        }

        val bindings = mutableListOf<SpirvBindingDescriptor>()
        source.bufferBlocks.forEach { blk ->
            if (blk.binding != null) {
                val kind = if (blk.storage == BufferStorageKind.BUFFER) "storage_buffer" else "uniform_buffer"
                bindings += SpirvBindingDescriptor(0, blk.binding, kind, blk.name, blk.packing.name.lowercase(), stage)
            }
        }
        source.declaredSamplers.forEach { samp ->
            if (samp.binding != null) {
                bindings += SpirvBindingDescriptor(0, samp.binding, "sampler", samp.name, samp.type, stage)
            }
        }

        val interfaces = source.interfaceVariables.map { v ->
            SpirvInterfaceLocation(
                location = v.location ?: -1,
                direction = v.direction.name.lowercase(),
                name = v.name,
                type = v.type,
                interpolation = v.interpolation.name.lowercase(),
            )
        }

        return SpirvAuditReport(
            file = file.physicalRelative,
            isSpirvCompliant = findings.none { it.severity == Severity.ERROR },
            bindings = bindings.sortedBy { it.binding },
            interfaces = interfaces.sortedBy { it.location },
            findings = findings.map { SpirvFinding(it.rule, it.severity.label, it.version, it.file, it.line, it.message) },
        )
    }
}
