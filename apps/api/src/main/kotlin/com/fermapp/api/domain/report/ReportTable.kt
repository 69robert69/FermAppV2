package com.fermapp.api.domain.report

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A report table, shared by JSON, PDF and Excel output (reportBuilders.ts). Optional parts are
 * left out of the JSON when they are null, as the TypeScript objects left them undefined.
 *
 * @param rows each row maps a column key to a string, a number or null
 * @param orientation wide tables print in landscape; the user may still choose
 * @param summary headline figures printed above the table
 */
@JvmRecord
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReportTable(
    val title: String,
    val period: String,
    val columns: List<Column>,
    val rows: List<Map<String, Any?>>,
    val totals: Map<String, Any?>?,
    val orientation: String?,
    val chart: Chart?,
    val summary: List<SummaryItem>?,
) {

    /** A column. [width] is the relative width in PDF tables; [money] marks an amount in euro. */
    @JvmRecord
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Column(
        val key: String,
        val label: String,
        val width: Double,
        // Named explicitly so that the ignored isNumeric()/isMoney() helpers below cannot hide them:
        // the phone aligns numeric columns and formats money columns from these two flags.
        @get:JsonProperty("numeric") val numeric: Boolean?,
        @get:JsonProperty("money") val money: Boolean?,
    ) {

        @JsonIgnore
        fun isNumeric(): Boolean = numeric == true

        @JsonIgnore
        fun isMoney(): Boolean = money == true

        companion object {
            @JvmStatic
            fun text(key: String, label: String, width: Double) = Column(key, label, width, null, null)

            @JvmStatic
            fun number(key: String, label: String, width: Double) = Column(key, label, width, true, null)

            @JvmStatic
            fun money(key: String, label: String, width: Double) = Column(key, label, width, true, true)
        }
    }

    /** Bars read from the columns [labelKey]/[valueKey] of each row, or from [points]. */
    @JvmRecord
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Chart(
        val title: String,
        val labelKey: String?,
        val valueKey: String?,
        val points: List<Point>?,
        val money: Boolean?,
        val unit: String?,
    )

    @JvmRecord
    data class Point(val label: String, val value: Double)

    @JvmRecord
    data class SummaryItem(val label: String, val value: String)
}
