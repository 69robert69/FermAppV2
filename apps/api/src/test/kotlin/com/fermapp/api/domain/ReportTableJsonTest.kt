package com.fermapp.api.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fermapp.api.common.json.JsonFormats
import com.fermapp.api.domain.report.ReportTable.Column
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Report columns carry "numeric" and "money" in the JSON, as the TypeScript API sent them:
 * the phone right-aligns numeric columns and formats money columns in euro.
 * The Java rewrite had lost both flags; this pins them, with and without the Kotlin module.
 */
class ReportTableJsonTest {

    private val columns = listOf(Column.text("a", "A", 1.0), Column.number("b", "B", 0.5), Column.money("c", "C", 2.0))
    private val expected = """[{"key":"a","label":"A","width":1},""" +
        """{"key":"b","label":"B","width":0.5,"numeric":true},""" +
        """{"key":"c","label":"C","width":2,"numeric":true,"money":true}]"""

    @Test
    fun `columns keep their flags with the Kotlin module`() {
        val mapper = ObjectMapper().registerModule(JsonFormats.module()).registerKotlinModule()
        assertEquals(expected, mapper.writeValueAsString(columns))
    }

    @Test
    fun `columns keep their flags with plain Jackson`() {
        val mapper = ObjectMapper().registerModule(JsonFormats.module())
        assertEquals(expected, mapper.writeValueAsString(columns))
    }
}
