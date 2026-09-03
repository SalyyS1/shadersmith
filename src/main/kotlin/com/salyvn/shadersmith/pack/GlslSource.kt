package com.salyvn.shadersmith.pack

import com.salyvn.shadersmith.contract.BlockMember

/** A `layout(std140) uniform Name { ... }` block as declared in a source file, with the line it starts on. */
data class DeclaredBlock(val name: String, val members: List<BlockMember>, val line: Int)

/** An `#include` / `#moj_import` line: which directive, what it names, and where. */
data class IncludeDirective(val directive: String, val target: String, val line: Int)

/**
 * The parts of a GLSL file that the version contract is about.
 *
 * This is deliberately not a GLSL parser. The contract violations that actually bite are structural —
 * a directive spelling, a `#version` line where none is allowed, a block member out of order — and
 * every one of them sits on a line that a regular expression finds without needing to understand the
 * language. A parser would find the same lines slower and fail on the preprocessor tricks packs use.
 */
class GlslSource(val text: String) {
    private val lines: List<String> = text.lines()

    /** The `#version N` line, if the file has one. */
    val version: Pair<Int, Int>? by lazy {
        lines.withIndex().firstNotNullOfOrNull { (index, line) ->
            VERSION.find(line)?.let { match -> match.groupValues[1].toInt() to index + 1 }
        }
    }

    val includes: List<IncludeDirective> by lazy {
        lines.withIndex().mapNotNull { (index, line) ->
            INCLUDE.find(line)?.let { match -> IncludeDirective(match.groupValues[1], match.groupValues[2], index + 1) }
        }
    }

    val blocks: List<DeclaredBlock> by lazy {
        BLOCK.findAll(text).map { match ->
            val members = match.groupValues[2].lines().mapNotNull { line ->
                MEMBER.find(line)?.let { member -> BlockMember(member.groupValues[1], member.groupValues[2]) }
            }
            DeclaredBlock(match.groupValues[1], members, text.lineNumberAt(match.range.first))
        }.toList()
    }

    /** `in`/`out` declarations at file scope that lack `layout(location = N)`, with their lines. */
    val unqualifiedInterfaceVariables: List<Pair<String, Int>> by lazy {
        lines.withIndex().mapNotNull { (index, line) ->
            val trimmed = line.trim()
            if (BARE_IO.matches(trimmed)) trimmed to index + 1 else null
        }
    }

    /** `gl_VertexID` occurrences, which 26.3 renamed. */
    val vertexIdLines: List<Int> by lazy {
        lines.withIndex().filter { (_, line) -> "gl_VertexID" in line }.map { it.index + 1 }
    }

    /** `uniform sampler2D Name;` declarations, for cross-checking a post effect's inputs. */
    val samplers: List<String> by lazy {
        SAMPLER.findAll(text).map { it.groupValues[1] }.toList()
    }

    companion object {
        private val VERSION = Regex("^\\s*#version\\s+(\\d+)")
        private val INCLUDE = Regex("^\\s*#(moj_import|include)\\s*[<\"]([^>\"]+)[>\"]")
        private val BLOCK = Regex("layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)\\s*\\{([^}]*)\\}")
        private val MEMBER = Regex("^\\s*(\\w+)\\s+(\\w+)\\s*;")
        private val BARE_IO = Regex("^(in|out)\\s+\\w+\\s+\\w+\\s*;\\s*(//.*)?$")
        private val SAMPLER = Regex("uniform\\s+sampler\\w*\\s+(\\w+)\\s*;")

        private fun String.lineNumberAt(offset: Int): Int = substring(0, offset).count { it == '\n' } + 1
    }
}
