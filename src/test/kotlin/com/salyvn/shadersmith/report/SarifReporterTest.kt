package com.salyvn.shadersmith.report

import com.github.ajalt.clikt.testing.test
import com.salyvn.shadersmith.cli.CheckCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SarifReporterTest {

    @Test
    fun `check format sarif emits OASIS SARIF v2_1_0 with rich rule descriptors and SRCROOT uris`() {
        val res = CheckCommand().test(
            listOf(
                Path.of("src/test/resources/oldpack").toString(),
                "--mc",
                "26.3",
                "--format",
                "sarif",
            ),
        )
        assertEquals(1, res.statusCode)
        val root = Json.parseToJsonElement(res.stdout).jsonObject
        assertEquals("2.1.0", root.getValue("version").jsonPrimitive.content)

        val run = root.getValue("runs").jsonArray.single().jsonObject
        val driver = run.getValue("tool").jsonObject.getValue("driver").jsonObject
        assertEquals("shadersmith", driver.getValue("name").jsonPrimitive.content)

        val rules = driver.getValue("rules").jsonArray.map { it.jsonObject }
        assertTrue(rules.isNotEmpty())
        val sm041 = rules.single { it.getValue("id").jsonPrimitive.content == "SM041" }
        assertEquals("UniformBlockSchemaMismatch", sm041.getValue("name").jsonPrimitive.content)
        assertTrue(
            sm041.getValue("shortDescription").jsonObject.getValue("text").jsonPrimitive.content.contains("std140"),
        )

        val results = run.getValue("results").jsonArray.map { it.jsonObject }
        assertTrue(results.isNotEmpty())
        val firstLoc = results.first()
            .getValue("locations").jsonArray.single().jsonObject
            .getValue("physicalLocation").jsonObject
            .getValue("artifactLocation").jsonObject
        assertEquals("%SRCROOT%", firstLoc.getValue("uriBaseId").jsonPrimitive.content)
        assertTrue(!firstLoc.getValue("uri").jsonPrimitive.content.startsWith("/"))
    }
}
