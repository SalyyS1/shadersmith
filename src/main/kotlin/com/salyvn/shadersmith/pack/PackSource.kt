package com.salyvn.shadersmith.pack

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** One file inside a pack: its path relative to the pack root, and its text. */
data class PackFile(val relative: String, val text: String) {
    /** `assets/<ns>/...` -> `ns`, or null for a path outside `assets/`. */
    val namespace: String? get() = NAMESPACE.matchEntire(relative)?.groupValues?.get(1)

    /** `core`, `include` or `post` for a shader, or null. */
    val shaderDirectory: String? get() = SHADER_PATH.matchEntire(relative)?.groupValues?.get(2)

    val fileName: String get() = relative.substringAfterLast('/')

    val isPostEffectDefinition: Boolean get() = POST_EFFECT_PATH.matches(relative)

    val isShaderSource: Boolean get() = shaderDirectory != null && fileName.substringAfterLast('.') in SOURCE_EXTENSIONS

    val isGlslInclude: Boolean get() = shaderDirectory == "include"

    companion object {
        private val NAMESPACE = Regex("assets/([a-z0-9_.-]+)/.+")
        private val SHADER_PATH = Regex("assets/([a-z0-9_.-]+)/shaders/(core|include|post)/(.+)")
        private val POST_EFFECT_PATH = Regex("assets/[a-z0-9_.-]+/post_effect/[^/]+\\.json")
        private val SOURCE_EXTENSIONS = setOf("vsh", "fsh", "glsl")
    }
}

/**
 * A resource pack as a list of the files that matter here, read from a directory or a zip.
 *
 * Only shader sources, shader-adjacent JSON and `pack.mcmeta` are loaded; textures and models are left on
 * disk. Paths are normalised to forward slashes so a finding reads the same on every platform.
 */
class PackSource private constructor(val root: Path, val files: List<PackFile>) {
    fun find(relative: String): PackFile? = files.firstOrNull { it.relative == relative }

    val shaderSources: List<PackFile> get() = files.filter { it.isShaderSource }

    val postEffectDefinitions: List<PackFile> get() = files.filter { it.isPostEffectDefinition }

    val packMeta: PackFile? get() = find("pack.mcmeta")

    companion object {
        private val INTERESTING = setOf("vsh", "fsh", "glsl", "json", "mcmeta")

        fun read(path: Path): PackSource {
            if (path.isDirectory()) return fromDirectory(path)
            if (path.isRegularFile() && path.extension == "zip") return fromZip(path)
            throw IOException("$path is neither a pack directory nor a .zip")
        }

        private fun fromDirectory(root: Path): PackSource {
            val files = Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() && it.extension in INTERESTING }
                    .map { file ->
                        val relative = root.relativize(file).joinToString("/") { it.toString() }
                        PackFile(relative, Files.readString(file, StandardCharsets.UTF_8))
                    }
                    .sorted(compareBy { it.relative })
                    .toList()
            }
            return PackSource(root, files)
        }

        private fun fromZip(zip: Path): PackSource {
            FileSystems.newFileSystem(zip).use { fs ->
                val zipRoot = fs.getPath("/")
                val files = Files.walk(zipRoot).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.toString().substringAfterLast('.') in INTERESTING }
                        .map { file ->
                            val relative = zipRoot.relativize(file).toString().replace('\\', '/').trimStart('/')
                            PackFile(relative, Files.readString(file, StandardCharsets.UTF_8))
                        }
                        .sorted(compareBy { it.relative })
                        .toList()
                }
                return PackSource(zip, files)
            }
        }
    }
}
