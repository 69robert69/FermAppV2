package com.fermapp.api.modules.reports

import com.fermapp.api.common.util.Js
import com.fermapp.api.domain.economics.Money
import com.fermapp.api.domain.report.ReportBuilders
import com.fermapp.api.domain.report.ReportTable
import com.fermapp.api.domain.report.ReportTable.Column
import com.fermapp.api.domain.sumOfDoubles
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component

/**
 * PDF and Excel output of a report (reportToPdf / reportToExcel). The PDF reproduces the pdfkit
 * layout: dark header band with the FermApp mark, title and period, summary boxes, a bar chart,
 * a table that continues on new pages and a footer with page numbers. DejaVu Sans is embedded
 * because it has the Romanian diacritics the standard PDF fonts lack.
 */
@Component
class ReportRenderer {

    // ------------------------------------------------------------------ Excel

    fun toExcel(report: ReportTable, farmName: String?): ByteArray {
        try {
            XSSFWorkbook().use { workbook ->
                ByteArrayOutputStream().use { out ->
                    workbook.properties.coreProperties.creator = "FermApp"
                    val title = if (report.title.length > 31) report.title.substring(0, 31) else report.title
                    val sheet = workbook.createSheet(title.replace(Regex("""[\[\]:*?/\\]"""), " "))

                    val titleFont = workbook.createFont()
                    titleFont.bold = true
                    titleFont.fontHeightInPoints = 14.toShort()
                    val titleStyle = workbook.createCellStyle()
                    titleStyle.setFont(titleFont)
                    val bold = workbook.createFont()
                    bold.bold = true
                    val boldStyle = workbook.createCellStyle()
                    boldStyle.setFont(bold)
                    val headerStyle = workbook.createCellStyle()
                    headerStyle.setFont(bold)
                    headerStyle.setFillForegroundColor(XSSFColor(byteArrayOf(0xE7.toByte(), 0xE2.toByte(), 0xCC.toByte()), null))
                    headerStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                    headerStyle.borderBottom = BorderStyle.NONE
                    val moneyStyle = workbook.createCellStyle()
                    moneyStyle.dataFormat = workbook.createDataFormat().getFormat("\"€\"#,##0.00")
                    val moneyBoldStyle = workbook.createCellStyle()
                    moneyBoldStyle.cloneStyleFrom(moneyStyle)
                    moneyBoldStyle.setFont(bold)

                    var rowIndex = 0
                    val titleRow = sheet.createRow(rowIndex++)
                    val titleCell = titleRow.createCell(0)
                    titleCell.setCellValue(report.title + " - " + farmName)
                    titleCell.cellStyle = titleStyle
                    sheet.createRow(rowIndex++).createCell(0).setCellValue(report.period)
                    val summary = report.summary ?: listOf()
                    for (item in summary) {
                        val row = sheet.createRow(rowIndex++)
                        row.createCell(0).setCellValue(item.label)
                        row.createCell(1).setCellValue(item.value)
                    }
                    sheet.createRow(rowIndex++)

                    val header = sheet.createRow(rowIndex++)
                    report.columns.forEachIndexed { i, column ->
                        val cell = header.createCell(i)
                        cell.setCellValue(column.label)
                        cell.cellStyle = headerStyle
                    }
                    for (values in report.rows) {
                        val row = sheet.createRow(rowIndex++)
                        report.columns.forEachIndexed { i, column ->
                            writeCell(row.createCell(i), values[column.key], if (column.isMoney()) moneyStyle else null)
                        }
                    }
                    val totals = report.totals
                    if (totals != null) {
                        val row = sheet.createRow(rowIndex)
                        report.columns.forEachIndexed { i, column ->
                            val cell = row.createCell(i)
                            writeCell(cell, if (i == 0) "Total" else totals[column.key], null)
                            cell.cellStyle = if (column.isMoney() && cell.cellType == CellType.NUMERIC) moneyBoldStyle else boldStyle
                        }
                    }
                    report.columns.forEachIndexed { i, column ->
                        val width = max(12.0, column.width * 16)
                        sheet.setColumnWidth(i, Math.round(width * 256).toInt())
                    }
                    sheet.createFreezePane(0, 4 + summary.size)
                    workbook.write(out)
                    return out.toByteArray()
                }
            }
        } catch (error: IOException) {
            throw UncheckedIOException(error)
        }
    }

    // ------------------------------------------------------------------ PDF

    /** Text drawing with pdfkit's top-left coordinates on a PDFBox page. */
    private class Canvas(
        private val document: PDDocument,
        private val regular: PDFont,
        private val bold: PDFont,
        landscape: Boolean,
    ) : AutoCloseable {
        val width: Float = if (landscape) PDRectangle.A4.height else PDRectangle.A4.width
        val height: Float = if (landscape) PDRectangle.A4.width else PDRectangle.A4.height
        private var stream: PDPageContentStream? = null
        var y = 0f
        private var lastLineHeight = 0f

        fun addPage() {
            stream?.close()
            val page = PDPage(PDRectangle(width, height))
            document.addPage(page)
            stream = PDPageContentStream(document, page)
            y = MARGIN
        }

        private fun out(): PDPageContentStream = stream!!

        fun font(useBold: Boolean): PDFont = if (useBold) bold else regular

        fun lineHeight(font: PDFont, size: Float): Float =
            (font.fontDescriptor.ascent - font.fontDescriptor.descent) / 1000f * size

        fun textWidth(font: PDFont, size: Float, text: String): Float = font.getStringWidth(text) / 1000f * size

        /** Wraps the text into lines that fit the width, breaking on spaces and, if needed, inside words. */
        fun wrap(font: PDFont, size: Float, text: String, maxWidth: Float): List<String> {
            val lines = mutableListOf<String>()
            for (paragraph in text.split("\n")) {
                val line = StringBuilder()
                for (word in paragraph.split(" ")) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (textWidth(font, size, candidate) <= maxWidth || line.isEmpty() && textWidth(font, size, word) <= maxWidth) {
                        line.setLength(0)
                        line.append(candidate)
                        continue
                    }
                    if (line.isNotEmpty()) {
                        lines += line.toString()
                        line.setLength(0)
                    }
                    var rest = word
                    while (textWidth(font, size, rest) > maxWidth && rest.length > 1) {
                        var cut = rest.length - 1
                        while (cut > 1 && textWidth(font, size, rest.substring(0, cut)) > maxWidth) {
                            cut--
                        }
                        lines += rest.substring(0, cut)
                        rest = rest.substring(cut)
                    }
                    line.append(rest)
                }
                lines += line.toString()
            }
            return lines
        }

        fun heightOf(font: PDFont, size: Float, text: String, maxWidth: Float): Float =
            wrap(font, size, text.ifEmpty { " " }, maxWidth).size * lineHeight(font, size)

        fun fillRect(x: Float, top: Float, w: Float, h: Float, color: Color) {
            val stream = out()
            stream.setNonStrokingColor(color)
            stream.addRect(x, height - top - h, w, h)
            stream.fill()
        }

        fun fillRoundedRect(x: Float, top: Float, w: Float, h: Float, r: Float, color: Color) {
            val stream = out()
            val radius = min(r, min(w, h) / 2)
            val bottom = height - top - h
            val k = 0.5523f * radius
            stream.setNonStrokingColor(color)
            stream.moveTo(x + radius, bottom)
            stream.lineTo(x + w - radius, bottom)
            stream.curveTo(x + w - radius + k, bottom, x + w, bottom + radius - k, x + w, bottom + radius)
            stream.lineTo(x + w, bottom + h - radius)
            stream.curveTo(x + w, bottom + h - radius + k, x + w - radius + k, bottom + h, x + w - radius, bottom + h)
            stream.lineTo(x + radius, bottom + h)
            stream.curveTo(x + radius - k, bottom + h, x, bottom + h - radius + k, x, bottom + h - radius)
            stream.lineTo(x, bottom + radius)
            stream.curveTo(x, bottom + radius - k, x + radius - k, bottom, x + radius, bottom)
            stream.closePath()
            stream.fill()
        }

        fun fillCircle(cx: Float, cy: Float, r: Float, color: Color) {
            val stream = out()
            val k = 0.5523f * r
            val y0 = height - cy
            stream.setNonStrokingColor(color)
            stream.moveTo(cx + r, y0)
            stream.curveTo(cx + r, y0 + k, cx + k, y0 + r, cx, y0 + r)
            stream.curveTo(cx - k, y0 + r, cx - r, y0 + k, cx - r, y0)
            stream.curveTo(cx - r, y0 - k, cx - k, y0 - r, cx, y0 - r)
            stream.curveTo(cx + k, y0 - r, cx + r, y0 - k, cx + r, y0)
            stream.closePath()
            stream.fill()
        }

        fun line(x1: Float, top1: Float, x2: Float, top2: Float, lineWidth: Float, color: Color) {
            val stream = out()
            stream.setStrokingColor(color)
            stream.setLineWidth(lineWidth)
            stream.moveTo(x1, height - top1)
            stream.lineTo(x2, height - top2)
            stream.stroke()
        }

        /** Draws one line of text whose box starts at [top]. */
        fun textLine(font: PDFont, size: Float, color: Color, x: Float, top: Float, text: String?) {
            val stream = out()
            val baseline = height - top - font.fontDescriptor.ascent / 1000f * size
            stream.beginText()
            stream.setFont(font, size)
            stream.setNonStrokingColor(color)
            stream.newLineAtOffset(x, baseline)
            stream.showText(text)
            stream.endText()
        }

        /** Wrapped text in a box; returns the height used. */
        fun text(font: PDFont, size: Float, color: Color, x: Float, top: Float, boxWidth: Float, text: String, align: String): Float {
            val lineHeight = lineHeight(font, size)
            var current = top
            for (line in wrap(font, size, text, boxWidth)) {
                val offset = when (align) {
                    "right" -> boxWidth - textWidth(font, size, line)
                    "center" -> (boxWidth - textWidth(font, size, line)) / 2
                    else -> 0f
                }
                textLine(font, size, color, x + offset, current, line)
                current += lineHeight
            }
            lastLineHeight = lineHeight
            return current - top
        }

        /** A single line, cut with an ellipsis when it does not fit. */
        fun textNoWrap(
            font: PDFont,
            size: Float,
            color: Color,
            x: Float,
            top: Float,
            boxWidth: Float,
            text: String,
            align: String,
            ellipsis: Boolean,
        ) {
            var value = text
            if (ellipsis && textWidth(font, size, value) > boxWidth) {
                while (value.isNotEmpty() && textWidth(font, size, "$value…") > boxWidth) {
                    value = value.substring(0, value.length - 1)
                }
                value = "$value…"
            }
            val offset = if (align == "right") boxWidth - textWidth(font, size, value) else 0f
            textLine(font, size, color, x + offset, top, value)
            lastLineHeight = lineHeight(font, size)
        }

        fun moveDown(lines: Float) {
            y += lines * lastLineHeight
        }

        override fun close() {
            stream?.close()
        }
    }

    private fun loadFont(document: PDDocument, name: String): PDFont =
        (ReportRenderer::class.java.getResourceAsStream("/fonts/$name") ?: throw IOException("Missing font $name")).use {
            PDType0Font.load(document, it)
        }

    fun toPdf(report: ReportTable, farmName: String?, orientation: String?): ByteArray {
        val layout = orientation ?: report.orientation ?: "landscape"
        val generated = ZonedDateTime.now().format(GENERATED)
        try {
            PDDocument().use { document ->
                ByteArrayOutputStream().use { out ->
                    val regular = loadFont(document, "DejaVuSans.ttf")
                    val bold = loadFont(document, "DejaVuSans-Bold.ttf")
                    Canvas(document, regular, bold, layout == "landscape").use { doc -> drawReport(doc, report, farmName, generated, regular, bold) }

                    val count = document.numberOfPages
                    for (i in 0 until count) {
                        val page = document.getPage(i)
                        val pageWidth = page.mediaBox.width
                        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { footer ->
                            val text = "FermApp, " + farmName + ", generated " + generated + "    Page " + (i + 1) + " of " + count
                            val size = 7f
                            val textWidth = regular.getStringWidth(text) / 1000f * size
                            val x = MARGIN + (pageWidth - MARGIN * 2) - textWidth
                            // pdfkit placed the line box at pageHeight - 30 from the top, that is 30 pt above the bottom edge.
                            val baseline = 30 - regular.fontDescriptor.ascent / 1000f * size
                            footer.beginText()
                            footer.setFont(regular, size)
                            footer.setNonStrokingColor(hex("#5b6150"))
                            footer.newLineAtOffset(x, baseline)
                            footer.showText(text)
                            footer.endText()
                        }
                    }
                    document.save(out)
                    return out.toByteArray()
                }
            }
        } catch (error: IOException) {
            throw UncheckedIOException(error)
        }
    }

    private fun drawReport(doc: Canvas, report: ReportTable, farmName: String?, generated: String, regular: PDFont, bold: PDFont) {
        doc.addPage()
        val left = MARGIN
        val tableWidth = doc.width - MARGIN * 2
        val bottom = doc.height - MARGIN - 18
        val totalWeight = report.columns.sumOfDoubles { it.width }
        val widths = FloatArray(report.columns.size) { i -> (report.columns[i].width / totalWeight * tableWidth).toFloat() }

        // Header band in the app colours, with the FermApp mark.
        doc.fillRect(0f, 0f, doc.width, 58f, hex("#181B13"))
        doc.fillCircle(left + 14, 29f, 13f, hex("#E2B23D"))
        doc.textLine(bold, 13f, hex("#181B13"), left + 9.5f, 21.5f, "F")
        doc.textLine(bold, 14f, hex("#F0ECDB"), left + 36, 18f, "FermApp")
        doc.textLine(regular, 8f, hex("#ACAE96"), left + 36, 36f, farmName)
        doc.textNoWrap(regular, 8f, hex("#ACAE96"), left, 25f, tableWidth, "Generated $generated", "right", false)
        doc.y = 78f
        doc.y += doc.text(bold, 16f, hex("#22261d"), left, doc.y, tableWidth, report.title, "left")
        doc.y += doc.text(regular, 9f, hex("#5b6150"), left, doc.y, tableWidth, report.period, "left")
        doc.moveDown(0.6f)

        val summary = report.summary ?: listOf()
        if (summary.isNotEmpty()) {
            val boxWidth = (tableWidth - (summary.size - 1) * 8) / summary.size
            val top = doc.y
            summary.forEachIndexed { i, item ->
                val x = left + i * (boxWidth + 8)
                doc.fillRoundedRect(x, top, boxWidth, 40f, 4f, hex("#f2eedd"))
                doc.textNoWrap(regular, 7.5f, hex("#5b6150"), x + 8, top + 7, boxWidth - 16, item.label, "left", false)
                doc.textNoWrap(bold, 12f, hex("#22261d"), x + 8, top + 20, boxWidth - 16, item.value, "left", false)
            }
            doc.y = top + 52
        }

        val points = ReportBuilders.reportChartPoints(report, 12)
        val chart = report.chart
        if (chart != null && points.isNotEmpty()) {
            doc.y += doc.text(bold, 10f, hex("#22261d"), left, doc.y, tableWidth, chart.title, "left")
            doc.moveDown(0.3f)
            val maxValue = points.maxOf { abs(it.value) }
            val labelWidth = min(160f, tableWidth * 0.3f)
            val valueWidth = 80f
            val barSpace = tableWidth - labelWidth - valueWidth - 12
            for (point in points) {
                val top = doc.y
                val barWidth = if (maxValue > 0) (abs(point.value) / maxValue * barSpace).toFloat() else 0f
                doc.textNoWrap(regular, 8f, hex("#22261d"), left, top + 1, labelWidth - 6, point.label, "left", true)
                doc.fillRoundedRect(
                    left + labelWidth, top + 1, max(2f, barWidth), 9f, 2f,
                    if (point.value < 0) hex("#D93C74") else hex("#C99A2E"),
                )
                val unit = if (chart.unit != null) " " + chart.unit else ""
                val value = if (chart.money == true) {
                    Money.formatEuro(point.value, 0)
                } else {
                    Js.num(Math.round(point.value * 10) / 10.0) + unit
                }
                doc.textNoWrap(regular, 8f, hex("#22261d"), left + labelWidth + barSpace + 12, top + 1, valueWidth, value, "right", false)
                doc.y = top + 14
            }
            doc.moveDown(0.8f)
        }

        drawRow(doc, report, widths, left, tableWidth, bottom, report.columns.map { it.label }, true, hex("#e7e2cc"))
        if (report.rows.isEmpty()) {
            doc.moveDown(1f)
            doc.y += doc.text(regular, 10f, hex("#5b6150"), left, doc.y, tableWidth, "There is no data for the selected period.", "left")
        }
        for (row in report.rows) {
            drawRow(doc, report, widths, left, tableWidth, bottom, report.columns.map { cellText(it, row[it.key]) }, false, null)
        }
        val totals = report.totals
        if (totals != null && report.rows.isNotEmpty()) {
            val cells = report.columns.mapIndexed { i, column -> if (i == 0) "Total" else cellText(column, totals[column.key]) }
            drawRow(doc, report, widths, left, tableWidth, bottom, cells, true, hex("#f2eedd"))
        }
    }

    private companion object {
        const val MARGIN = 36f
        const val PADDING = 4f
        val GENERATED: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.UK)

        fun hex(value: String): Color = Color.decode(value)

        fun writeCell(cell: Cell, value: Any?, numberStyle: CellStyle?) {
            if (value is Number) {
                cell.setCellValue(value.toDouble())
                if (numberStyle != null) {
                    cell.cellStyle = numberStyle
                }
            } else {
                cell.setCellValue(value?.toString() ?: "")
            }
        }

        fun cellText(column: Column, value: Any?): String = when {
            column.isMoney() && value is Number -> Money.formatEuro(value.toDouble(), 2)
            value == null -> ""
            value is Number -> Js.num(value.toDouble())
            else -> value.toString()
        }

        fun drawRow(
            doc: Canvas,
            report: ReportTable,
            widths: FloatArray,
            left: Float,
            tableWidth: Float,
            bottom: Float,
            cells: List<String>,
            useBold: Boolean,
            fill: Color?,
        ) {
            val font = doc.font(useBold)
            val size = 8f
            var rowHeight = 0f
            cells.forEachIndexed { i, cell -> rowHeight = max(rowHeight, doc.heightOf(font, size, cell, widths[i] - PADDING * 2)) }
            rowHeight += PADDING * 2
            if (doc.y + rowHeight > bottom) {
                doc.addPage()
                doc.y = MARGIN
            }
            val top = doc.y
            if (fill != null) {
                doc.fillRect(left, top, tableWidth, rowHeight, fill)
            }
            var x = left
            cells.forEachIndexed { i, cell ->
                doc.text(
                    font, size, hex("#22261d"), x + PADDING, top + PADDING, widths[i] - PADDING * 2, cell,
                    if (report.columns[i].isNumeric()) "right" else "left",
                )
                x += widths[i]
            }
            doc.line(left, top + rowHeight, left + tableWidth, top + rowHeight, 0.4f, hex("#c9c4ad"))
            doc.y = top + rowHeight
        }
    }
}
