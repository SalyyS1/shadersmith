package com.salyvn.shadersmith.pack

import com.salyvn.shadersmith.contract.BlockMember

/** A `layout(std140) uniform Name { ... }` block as declared in a source file, with the line it starts on. */
data class DeclaredBlock(val name: String, val members: List<BlockMember>, val line: Int)

/** An `#include` / `#moj_import` line: which directive, what it names, and where. */
data class IncludeDirective(val directive: String, val target: String, val line: Int)

/**
 * The parts of a GLSL file that the version contract is about.
 *
 * Line-preserving comment stripping removes `//` and block comments first so commented-out `#moj_import`,
 * `#version`, `gl_VertexID`, or `std140` blocks never trigger false findings while every line number stays exact.
 */
class GlslSource(val text: String) {
    private val rawLines: List<String> = text.lines()
    private val cleanText: String = stripCommentsPreservingLines(text)
    private val cleanLines: List<String> = cleanText.lines()

    /** The `#version N` line, if the file has one. */
    val version: Pair<Int, Int>? by lazy {
        cleanLines.withIndex().firstNotNullOfOrNull { (index, line) ->
            VERSION.find(line)?.let { match -> match.groupValues[1].toInt() to index + 1 }
        }
    }

    val includes: List<IncludeDirective> by lazy {
        cleanLines.withIndex().mapNotNull { (index, line) ->
            INCLUDE.find(line)?.let { match -> IncludeDirective(match.groupValues[1], match.groupValues[2], index + 1) }
        }
    }

    val blocks: List<DeclaredBlock> by lazy {
        BLOCK.findAll(cleanText).map { match ->
            val members = match.groupValues[2].lines().mapNotNull { line ->
                MEMBER.find(line)?.let { member -> BlockMember(member.groupValues[1], member.groupValues[2]) }
            }
            DeclaredBlock(match.groupValues[1], members, cleanText.lineNumberAt(match.range.first))
        }.toList()
    }

    /** `in`/`out` declarations at file scope that lack `layout(location = N)`, with their lines. */
    val unqualifiedInterfaceVariables: List<Pair<String, Int>> by lazy {
        cleanLines.withIndex().mapNotNull { (index, cleanLine) ->
            if (BARE_IO.matches(cleanLine.trim())) rawLines[index].trim() to index + 1 else null
        }
    }

    /** `gl_VertexID` occurrences outside comments, which 26.3 renamed to `gl_VertexIndex`. */
    val vertexIdLines: List<Int> by lazy {
        cleanLines.withIndex().filter { (_, line) -> VERTEX_ID.containsMatchIn(line) }.map { it.index + 1 }
    }

    /** `uniform sampler2D Name;` declarations outside comments, for cross-checking a post effect's inputs. */
    val samplers: List<String> by lazy {
        SAMPLER.findAll(cleanText).map { it.groupValues[1] }.toList()
    }

    companion object {
        private val VERSION = Regex("^\\s*#version\\s+(\\d+)")
        private val INCLUDE = Regex("^\\s*#(moj_import|include)\\s*[<\"]([^>\"]+)[>\"]")
        private val BLOCK = Regex("layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)\\s*\\{([^}]*)\\}")
        private val MEMBER = Regex("^\\s*(\\w+)\\s+(\\w+)\\s*;")
        private val BARE_IO = Regex("^(?:(?:flat|smooth|noperspective|centroid)\\s+)*(in|out)\\s+\\w+\\s+\\w+\\s*;$")
        private val VERTEX_ID = Regex("\\bgl_VertexID\\b")
        private val SAMPLER = Regex("uniform\\s+sampler\\w*\\s+(\\w+)\\s*;")

        private fun String.lineNumberAt(offset: Int): Int = substring(0, offset).count { it == '\n' } + 1

        /** Replaces `//...` and multi-line block comments with spaces while keeping every `\n` in place. */
        internal fun stripCommentsPreservingLines(input: String): String {
            val out = StringBuilder(input.length)
            var i = 0
            val n = input.length
            while (i < n) {
                val c = input[i]
                if (c == '/' && i + 1 < n && input[i + 1] == '/') {
                    out.append("  ")
                    i += 2
                    while (i < n && input[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                } else if (c == '/' && i + 1 < n && input[i + 1] == '*') {
                    out.append("  ")
                    i += 2
                    while (i < n) {
                        if (input[i] == '*' && i + 1 < n && input[i + 1] == '/') {
                            out.append("  ")
                            i += 2
                            break
                        }
                        out.append(if (input[i] == '\n') '\n' else ' ')
                        i++
                    }
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
