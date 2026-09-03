package com.salyvn.shadersmith.contract

import com.salyvn.shadersmith.pack.PackSource
import com.salyvn.shadersmith.report.Finding
import com.salyvn.shadersmith.report.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The fixture under src/test/resources/oldpack is a pack written against 26.2 in the shape plugins
 * actually ship: a text shader with imports, a gui shader with an inlined uniform block, an overridden
 * include, a stale program JSON and a hook into the transparency chain.
 */
class ContractCheckerTest {

    private val dataset = ContractDataset.load()
    private val pack = PackSource.read(Path.of("src/test/resources/oldpack"))

    private fun check(version: String): List<Finding> = ContractChecker(dataset.resolve(version)).check(pack)

    private fun List<Finding>.rules(): List<String> = map { it.rule }.distinct().sorted()

    @Test
    fun `on the version it was written for, only the stale LineWidth is an error`() {
        val findings = check("26.2")
        val errors = findings.filter { it.severity == Severity.ERROR }

        assertEquals(listOf("SM041"), errors.rules())
        assertTrue(errors.single().message.contains("LineWidth"))
        assertTrue(errors.single().file.endsWith("gui.vsh"))
    }

    @Test
    fun `on 1_21_10 the same block is exactly right and there are no errors`() {
        val findings = check("1.21.10")

        assertTrue(findings.none { it.severity == Severity.ERROR }, findings.joinToString("\n") { it.message })
    }

    @Test
    fun `on 26_3 every silent break is named`() {
        val rules = check("26.3-pre-1").filter { it.severity == Severity.ERROR }.rules()

        assertTrue("SM041" in rules, "block members differ")
        assertTrue("SM030" in rules, "#moj_import must become #include")
        assertTrue("SM020" in rules, "#version in an include")
        assertTrue("SM050" in rules, "bare in/out without layout(location)")
        assertTrue("SM051" in rules, "gl_VertexID rename")
    }

    @Test
    fun `a stale program json is a warning with a delete fix on every version after 1_21_4`() {
        val stale = check("26.2").filter { it.rule == "SM001" }

        assertEquals(1, stale.size)
        assertEquals(Severity.WARNING, stale.single().severity)
        assertTrue(stale.single().file.endsWith("rendertype_text.json"))
        assertTrue(check("1.21.4").none { it.rule == "SM001" }, "on 1.21.4 the file is still read")
    }

    @Test
    fun `the transparency hook is flagged only where the chain is gone`() {
        assertTrue(check("26.2").none { it.rule == "SM063" })
        assertEquals(1, check("26.3-pre-1").count { it.rule == "SM063" })
    }

    @Test
    fun `the mcmeta range is checked against the version`() {
        assertTrue(check("26.2").none { it.rule == "SM072" }, "88 is inside 63..88")
        assertEquals(1, check("26.3-pre-1").count { it.rule == "SM072" }, "97 is outside it")
    }

    @Test
    fun `replacing a vanilla include is a note, not an error`() {
        val note = check("26.2").single { it.rule == "SM010" }

        assertEquals(Severity.NOTE, note.severity)
        assertTrue(note.file.endsWith("include/fog.glsl"))
    }

    @Test
    fun `findings come worst first`() {
        val findings = check("26.3-pre-1")
        val severities = findings.map { it.severity.ordinal }

        assertEquals(severities.sorted(), severities)
    }
}
