package com.salyvn.shadersmith.pack

import com.salyvn.shadersmith.contract.OverlayEntry
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * One file inside a pack: its logical `assets/...` path (`relative`), its text, and its physical path from
 * the pack root (`physicalRelative`, which includes any `<overlay_dir>/` prefix when resolved through an
 * active overlay).
 */
data class PackFile(
    val relative: String,
    val text: String,
    val physicalRelative: String = relative,
) {
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

    /**
     * Computes the effective file map for [packFormat] by starting from base `assets/...` files and stacking
     * every matching [OverlayEntry] in declaration order, matching vanilla's `OverlayMetadataSection`.
     */
    fun effectiveFiles(packFormat: Int, overlays: List<OverlayEntry>): List<PackFile> {
        val merged = linkedMapOf<String, PackFile>()
        files.filter { it.relative.startsWith("assets/") }.forEach { base ->
            merged[base.relative] = base
        }
        overlays.filter { it.matches(packFormat) }.forEach { overlay ->
            val prefix = "${overlay.directory}/"
            files.filter { it.relative.startsWith("${prefix}assets/") }.forEach { over ->
                val logical = over.relative.removePrefix(prefix)
                merged[logical] = PackFile(
                    relative = logical,
                    text = over.text,
                    physicalRelative = over.physicalRelative,
                )
            }
        }
        return merged.values.sortedBy { it.relative }
    }

    fun hasOverlayDirectory(directory: String): Boolean {
        val prefix = "${directory.trim('/')}/"
        return files.any { it.relative.startsWith(prefix) }
    }

    companion object {
        private val INTERESTING = setOf("vsh", "fsh", "glsl", "json", "mcmeta")
        private const val MAX_ZIP_ENTRIES = 25_000
        private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024L
        private val FIXED_ZIP_TIME: FileTime = FileTime.fromMillis(315_532_800_000L)

        fun read(path: Path): PackSource {
            if (path.isDirectory()) return fromDirectory(path)
            if (path.isRegularFile() && path.extension.lowercase() == "zip") return fromZip(path)
            throw IOException("$path is neither a pack directory nor a .zip")
        }

        fun extractAllSafely(zipPath: Path, targetDir: Path) {
            val normalizedRoot = targetDir.toAbsolutePath().normalize()
            Files.createDirectories(normalizedRoot)
            ZipFile(zipPath.toFile()).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    count++
                    if (count > MAX_ZIP_ENTRIES) {
                        throw IOException("Zip pack $zipPath exceeds maximum entry count ($MAX_ZIP_ENTRIES)")
                    }
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val clean = entry.name.replace('\\', '/').trimStart('/')
                    if (clean.isEmpty() || clean.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                        throw IOException("Refusing unsafe zip entry '${entry.name}' in $zipPath")
                    }
                    val dest = normalizedRoot.resolve(clean).normalize()
                    if (!dest.startsWith(normalizedRoot)) {
                        throw IOException("Refusing unsafe zip entry '${entry.name}' in $zipPath")
                    }
                    Files.createDirectories(dest.parent)
                    zip.getInputStream(entry).use { input ->
                        val bytes = input.readNBytes((MAX_ENTRY_BYTES + 1).toInt())
                        if (bytes.size > MAX_ENTRY_BYTES) {
                            throw IOException("Zip entry '$clean' exceeds ${MAX_ENTRY_BYTES}B safety limit")
                        }
                        Files.write(dest, bytes)
                    }
                }
            }
        }

        fun writeDeterministicZip(sourceDir: Path, zipTarget: Path): String {
            val root = sourceDir.toAbsolutePath().normalize()
            val entries = Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() }
                    .map { file -> root.relativize(file).invariantSeparatorsPathString to file }
                    .toList()
                    .sortedBy { it.first }
            }
            zipTarget.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            BufferedOutputStream(Files.newOutputStream(zipTarget)).use { rawOut ->
                ZipOutputStream(rawOut).use { zos ->
                    for ((rel, file) in entries) {
                        val zEntry = ZipEntry(rel).apply { lastModifiedTime = FIXED_ZIP_TIME }
                        zos.putNextEntry(zEntry)
                        zos.write(Files.readAllBytes(file))
                        zos.closeEntry()
                    }
                }
            }
            val bytes = Files.readAllBytes(zipTarget)
            return MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
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
