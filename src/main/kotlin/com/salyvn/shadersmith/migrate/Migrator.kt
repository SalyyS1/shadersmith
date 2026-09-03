package com.salyvn.shadersmith.migrate

import com.salyvn.shadersmith.contract.ContractChecker
import com.salyvn.shadersmith.contract.VersionContract
import com.salyvn.shadersmith.pack.PackFile
import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Fix

/** What one file becomes: rewritten text, or null for "delete it". */
data class FileChange(val relative: String, val before: String, val after: String?, val applied: List<Finding>)

data class MigrationResult(
    val target: VersionContract,
    val changes: List<FileChange>,
    /** Findings that have no mechanical fix and still need a person. */
    val remaining: List<Finding>,
)

/**
 * Applies every fix the checker knows how to make, and reports what it could not.
 *
 * The rule is that a rewrite is only applied when it is the same edit a careful person would make by
 * hand — swapping a directive, reordering a block to the version's layout, dropping a file the client no
 * longer reads. Anything that needs judgement, like assigning `layout(location)` numbers, is left as a
 * finding, because a wrong guess there is worse than a clear error.
 */
class Migrator(private val target: VersionContract) {

    fun migrate(pack: PackSource): MigrationResult {
        val findings = ContractChecker(target).check(pack)
        val byFile = findings.groupBy { it.file }
        val changes = mutableListOf<FileChange>()
        val remaining = mutableListOf<Finding>()

        byFile.forEach { (relative, fileFindings) ->
            val file = pack.find(relative) ?: return@forEach
            val (fixable, manual) = fileFindings.partition { it.fix != null }
            remaining += manual
            if (fixable.isEmpty()) return@forEach
            changes += apply(file, fixable)
        }
        return MigrationResult(target, changes.sortedBy { it.relative }, remaining)
    }

    private fun apply(file: PackFile, findings: List<Finding>): FileChange {
        findings.firstOrNull { it.fix is Fix.DeleteFile }?.let {
            return FileChange(file.relative, file.text, null, findings)
        }
        // Line edits were computed against the original file, so they go first, bottom-up, while the line
        // numbers still mean what they meant. A block rewrite changes the line count, but it is applied by
        // pattern rather than by position, so it is safe to run afterwards on the edited text.
        var text = file.text
        val lineEdits = findings.mapNotNull { it.fix as? Fix.ReplaceLine }
        if (lineEdits.isNotEmpty()) {
            val lines = text.lines().toMutableList()
            lineEdits.sortedByDescending { it.line }.forEach { edit ->
                if (edit.line in 1..lines.size) {
                    if (edit.with.isEmpty()) lines.removeAt(edit.line - 1) else lines[edit.line - 1] = edit.with
                }
            }
            text = lines.joinToString("\n")
        }
        findings.mapNotNull { it.fix as? Fix.RewriteFile }.forEach { rewrite -> text = rewrite.apply(text) }
        return FileChange(file.relative, file.text, text, findings)
    }
}
