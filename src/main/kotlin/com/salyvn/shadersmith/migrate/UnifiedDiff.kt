package com.salyvn.shadersmith.migrate

/**
 * A unified diff of two texts, good enough to read and to feed `patch`.
 *
 * It is a plain longest-common-subsequence diff with three lines of context, which is all a shader file
 * needs — they are short, and the edits are single lines or one block.
 */
object UnifiedDiff {
    private const val CONTEXT = 3

    fun render(path: String, before: String, after: String?): String {
        val old = before.lines()
        val new = after?.lines() ?: emptyList()
        val ops = diff(old, new)
        val out = StringBuilder()
        out.append("--- a/$path\n")
        out.append(if (after == null) "+++ /dev/null\n" else "+++ b/$path\n")

        var index = 0
        while (index < ops.size) {
            if (ops[index].kind == Kind.SAME) {
                index++
                continue
            }
            val start = maxOf(0, index - CONTEXT)
            var end = index
            var sinceChange = 0
            while (end < ops.size && sinceChange < CONTEXT * 2) {
                if (ops[end].kind == Kind.SAME) sinceChange++ else sinceChange = 0
                end++
            }
            end = minOf(ops.size, end)
            val hunk = ops.subList(start, end)
            val oldStart = hunk.first().oldLine
            val newStart = hunk.first().newLine
            val oldCount = hunk.count { it.kind != Kind.ADD }
            val newCount = hunk.count { it.kind != Kind.DEL }
            out.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            hunk.forEach { op ->
                val prefix = when (op.kind) {
                    Kind.SAME -> " "
                    Kind.ADD -> "+"
                    Kind.DEL -> "-"
                }
                out.append(prefix).append(op.text).append('\n')
            }
            index = end
        }
        return out.toString()
    }

    private enum class Kind { SAME, ADD, DEL }

    private data class Op(val kind: Kind, val text: String, val oldLine: Int, val newLine: Int)

    private fun diff(old: List<String>, new: List<String>): List<Op> {
        val table = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) {
            for (j in new.indices.reversed()) {
                table[i][j] = if (old[i] == new[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < old.size && j < new.size) {
            when {
                old[i] == new[j] -> { ops += Op(Kind.SAME, old[i], i + 1, j + 1); i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> { ops += Op(Kind.DEL, old[i], i + 1, j + 1); i++ }
                else -> { ops += Op(Kind.ADD, new[j], i + 1, j + 1); j++ }
            }
        }
        while (i < old.size) { ops += Op(Kind.DEL, old[i], i + 1, j + 1); i++ }
        while (j < new.size) { ops += Op(Kind.ADD, new[j], i + 1, j + 1); j++ }
        return ops
    }
}
