package com.salyvn.shadersmith.report

enum class Severity(val label: String, val sarif: String) {
    /** The pack will not load, or will load and render the wrong thing with no error. */
    ERROR("error", "error"),

    /** Works today, breaks on the next version or under a common mod, or is dead weight. */
    WARNING("warning", "warning"),

    /** Worth knowing, nothing to do. */
    NOTE("note", "note"),
}

/**
 * One thing wrong with a pack for one version.
 *
 * Every finding carries a rule id so it can be suppressed or searched for, the file and line so it can
 * be clicked, and — where a fix is mechanical — the text that fixes it, which is what `migrate` applies.
 */
data class Finding(
    val rule: String,
    val severity: Severity,
    val version: String,
    val file: String,
    val line: Int?,
    val message: String,
    val fix: Fix? = null,
)

/** A mechanical rewrite: replace one line, delete a file, or transform a whole file. */
sealed class Fix {
    data class ReplaceLine(val line: Int, val with: String) : Fix()

    data class DeleteFile(val reason: String) : Fix()

    /**
     * A transformation over the whole text rather than a replacement, so it composes with line edits that
     * were computed against the original: it is applied last, to whatever the text has become.
     */
    class RewriteFile(val apply: (String) -> String) : Fix()
}
