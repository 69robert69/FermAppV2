package com.fermapp.api.modules.reports;

import com.fermapp.api.common.util.Js;
import com.fermapp.api.domain.economics.Money;
import com.fermapp.api.domain.report.ReportBuilders;
import com.fermapp.api.domain.report.ReportTable;
import com.fermapp.api.domain.report.ReportTable.Column;
import com.fermapp.api.domain.report.ReportTable.Point;
import com.fermapp.api.domain.report.ReportTable.SummaryItem;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * PDF and Excel output of a report (reportToPdf / reportToExcel). The PDF reproduces the pdfkit
 * layout: dark header band with the FermApp mark, title and period, summary boxes, a bar chart,
 * a table that continues on new pages and a footer with page numbers. DejaVu Sans is embedded
 * because it has the Romanian diacritics the standard PDF fonts lack.
 */
@Component
public class ReportRenderer {

    private static final float MARGIN = 36;
    private static final float PADDING = 4;
    private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.UK);

    // ------------------------------------------------------------------ Excel

    public byte[] toExcel(ReportTable report, String farmName) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.getProperties().getCoreProperties().setCreator("FermApp");
            String title = report.title().length() > 31 ? report.title().substring(0, 31) : report.title();
            Sheet sheet = workbook.createSheet(title.replaceAll("[\\[\\]:*?/\\\\]", " "));

            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle boldStyle = workbook.createCellStyle();
            boldStyle.setFont(bold);
            XSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(bold);
            headerStyle.setFillForegroundColor(new XSSFColor(new byte[] {(byte) 0xE7, (byte) 0xE2, (byte) 0xCC}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.NONE);
            CellStyle moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("\"€\"#,##0.00"));
            CellStyle moneyBoldStyle = workbook.createCellStyle();
            moneyBoldStyle.cloneStyleFrom(moneyStyle);
            moneyBoldStyle.setFont(bold);

            int rowIndex = 0;
            Row titleRow = sheet.createRow(rowIndex++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(report.title() + " - " + farmName);
            titleCell.setCellStyle(titleStyle);
            sheet.createRow(rowIndex++).createCell(0).setCellValue(report.period());
            List<SummaryItem> summary = report.summary() == null ? List.of() : report.summary();
            for (SummaryItem item : summary) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(item.label());
                row.createCell(1).setCellValue(item.value());
            }
            sheet.createRow(rowIndex++);

            Row header = sheet.createRow(rowIndex++);
            for (int i = 0; i < report.columns().size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(report.columns().get(i).label());
                cell.setCellStyle(headerStyle);
            }
            for (Map<String, Object> values : report.rows()) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < report.columns().size(); i++) {
                    Column column = report.columns().get(i);
                    writeCell(row.createCell(i), values.get(column.key()), column.isMoney() ? moneyStyle : null);
                }
            }
            if (report.totals() != null) {
                Row row = sheet.createRow(rowIndex);
                for (int i = 0; i < report.columns().size(); i++) {
                    Column column = report.columns().get(i);
                    Cell cell = row.createCell(i);
                    writeCell(cell, i == 0 ? "Total" : report.totals().get(column.key()), null);
                    cell.setCellStyle(column.isMoney() && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            ? moneyBoldStyle : boldStyle);
                }
            }
            for (int i = 0; i < report.columns().size(); i++) {
                double width = Math.max(12, report.columns().get(i).width() * 16);
                sheet.setColumnWidth(i, (int) Math.round(width * 256));
            }
            sheet.createFreezePane(0, 4 + summary.size());
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void writeCell(Cell cell, Object value, CellStyle numberStyle) {
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            if (numberStyle != null) {
                cell.setCellStyle(numberStyle);
            }
        } else {
            cell.setCellValue(value == null ? "" : value.toString());
        }
    }

    // ------------------------------------------------------------------ PDF

    /** Text drawing with pdfkit's top-left coordinates on a PDFBox page. */
    private static final class Canvas implements AutoCloseable {
        private final PDDocument document;
        private final PDFont regular;
        private final PDFont bold;
        private final float width;
        private final float height;
        private final List<PDPage> pages = new ArrayList<>();
        private PDPageContentStream stream;
        private float y;
        private float lastLineHeight;

        Canvas(PDDocument document, PDFont regular, PDFont bold, boolean landscape) {
            this.document = document;
            this.regular = regular;
            this.bold = bold;
            this.width = landscape ? PDRectangle.A4.getHeight() : PDRectangle.A4.getWidth();
            this.height = landscape ? PDRectangle.A4.getWidth() : PDRectangle.A4.getHeight();
        }

        void addPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            pages.add(page);
            stream = new PDPageContentStream(document, page);
            y = MARGIN;
        }

        PDFont font(boolean useBold) {
            return useBold ? bold : regular;
        }

        float lineHeight(PDFont font, float size) {
            return (font.getFontDescriptor().getAscent() - font.getFontDescriptor().getDescent()) / 1000f * size;
        }

        float textWidth(PDFont font, float size, String text) throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }

        /** Wraps the text into lines that fit the width, breaking on spaces and, if needed, inside words. */
        List<String> wrap(PDFont font, float size, String text, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String paragraph : text.split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split(" ", -1)) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (textWidth(font, size, candidate) <= maxWidth || line.isEmpty() && textWidth(font, size, word) <= maxWidth) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    String rest = word;
                    while (textWidth(font, size, rest) > maxWidth && rest.length() > 1) {
                        int cut = rest.length() - 1;
                        while (cut > 1 && textWidth(font, size, rest.substring(0, cut)) > maxWidth) {
                            cut--;
                        }
                        lines.add(rest.substring(0, cut));
                        rest = rest.substring(cut);
                    }
                    line.append(rest);
                }
                lines.add(line.toString());
            }
            return lines;
        }

        float heightOf(PDFont font, float size, String text, float maxWidth) throws IOException {
            return wrap(font, size, text.isEmpty() ? " " : text, maxWidth).size() * lineHeight(font, size);
        }

        void fillRect(float x, float top, float w, float h, Color color) throws IOException {
            stream.setNonStrokingColor(color);
            stream.addRect(x, height - top - h, w, h);
            stream.fill();
        }

        void fillRoundedRect(float x, float top, float w, float h, float r, Color color) throws IOException {
            float radius = Math.min(r, Math.min(w, h) / 2);
            float bottom = height - top - h;
            float k = 0.5523f * radius;
            stream.setNonStrokingColor(color);
            stream.moveTo(x + radius, bottom);
            stream.lineTo(x + w - radius, bottom);
            stream.curveTo(x + w - radius + k, bottom, x + w, bottom + radius - k, x + w, bottom + radius);
            stream.lineTo(x + w, bottom + h - radius);
            stream.curveTo(x + w, bottom + h - radius + k, x + w - radius + k, bottom + h, x + w - radius, bottom + h);
            stream.lineTo(x + radius, bottom + h);
            stream.curveTo(x + radius - k, bottom + h, x, bottom + h - radius + k, x, bottom + h - radius);
            stream.lineTo(x, bottom + radius);
            stream.curveTo(x, bottom + radius - k, x + radius - k, bottom, x + radius, bottom);
            stream.closePath();
            stream.fill();
        }

        void fillCircle(float cx, float cy, float r, Color color) throws IOException {
            float k = 0.5523f * r;
            float y0 = height - cy;
            stream.setNonStrokingColor(color);
            stream.moveTo(cx + r, y0);
            stream.curveTo(cx + r, y0 + k, cx + k, y0 + r, cx, y0 + r);
            stream.curveTo(cx - k, y0 + r, cx - r, y0 + k, cx - r, y0);
            stream.curveTo(cx - r, y0 - k, cx - k, y0 - r, cx, y0 - r);
            stream.curveTo(cx + k, y0 - r, cx + r, y0 - k, cx + r, y0);
            stream.closePath();
            stream.fill();
        }

        void line(float x1, float top1, float x2, float top2, float lineWidth, Color color) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(lineWidth);
            stream.moveTo(x1, height - top1);
            stream.lineTo(x2, height - top2);
            stream.stroke();
        }

        /** Draws one line of text whose box starts at {@code top}. */
        void textLine(PDFont font, float size, Color color, float x, float top, String text) throws IOException {
            float baseline = height - top - font.getFontDescriptor().getAscent() / 1000f * size;
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline);
            stream.showText(text);
            stream.endText();
        }

        /** Wrapped text in a box; returns the height used. */
        float text(PDFont font, float size, Color color, float x, float top, float boxWidth, String text, String align) throws IOException {
            float lineHeight = lineHeight(font, size);
            float current = top;
            for (String line : wrap(font, size, text, boxWidth)) {
                float offset = switch (align) {
                    case "right" -> boxWidth - textWidth(font, size, line);
                    case "center" -> (boxWidth - textWidth(font, size, line)) / 2;
                    default -> 0;
                };
                textLine(font, size, color, x + offset, current, line);
                current += lineHeight;
            }
            lastLineHeight = lineHeight;
            return current - top;
        }

        /** A single line, cut with an ellipsis when it does not fit. */
        void textNoWrap(PDFont font, float size, Color color, float x, float top, float boxWidth, String text, String align,
                        boolean ellipsis) throws IOException {
            String value = text;
            if (ellipsis && textWidth(font, size, value) > boxWidth) {
                while (!value.isEmpty() && textWidth(font, size, value + "…") > boxWidth) {
                    value = value.substring(0, value.length() - 1);
                }
                value = value + "…";
            }
            float offset = "right".equals(align) ? boxWidth - textWidth(font, size, value) : 0;
            textLine(font, size, color, x + offset, top, value);
            lastLineHeight = lineHeight(font, size);
        }

        void moveDown(float lines) {
            y += lines * lastLineHeight;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private static Color hex(String value) {
        return Color.decode(value);
    }

    private static String cellText(Column column, Object value) {
        if (column.isMoney() && value instanceof Number number) {
            return Money.formatEuro(number.doubleValue(), 2);
        }
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            return Js.num(number.doubleValue());
        }
        return value.toString();
    }

    private PDFont loadFont(PDDocument document, String name) throws IOException {
        try (InputStream in = ReportRenderer.class.getResourceAsStream("/fonts/" + name)) {
            if (in == null) {
                throw new IOException("Missing font " + name);
            }
            return PDType0Font.load(document, in);
        }
    }

    public byte[] toPdf(ReportTable report, String farmName, String orientation) {
        String layout = orientation != null ? orientation : (report.orientation() != null ? report.orientation() : "landscape");
        String generated = ZonedDateTime.now().format(GENERATED);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont regular = loadFont(document, "DejaVuSans.ttf");
            PDFont bold = loadFont(document, "DejaVuSans-Bold.ttf");
            try (Canvas doc = new Canvas(document, regular, bold, "landscape".equals(layout))) {
                doc.addPage();
                float left = MARGIN;
                float tableWidth = doc.width - MARGIN * 2;
                float bottom = doc.height - MARGIN - 18;
                double totalWeight = report.columns().stream().mapToDouble(Column::width).sum();
                float[] widths = new float[report.columns().size()];
                for (int i = 0; i < widths.length; i++) {
                    widths[i] = (float) (report.columns().get(i).width() / totalWeight * tableWidth);
                }

                // Header band in the app colours, with the FermApp mark.
                doc.fillRect(0, 0, doc.width, 58, hex("#181B13"));
                doc.fillCircle(left + 14, 29, 13, hex("#E2B23D"));
                doc.textLine(bold, 13, hex("#181B13"), left + 9.5f, 21.5f, "F");
                doc.textLine(bold, 14, hex("#F0ECDB"), left + 36, 18, "FermApp");
                doc.textLine(regular, 8, hex("#ACAE96"), left + 36, 36, farmName);
                doc.textNoWrap(regular, 8, hex("#ACAE96"), left, 25, tableWidth, "Generated " + generated, "right", false);
                doc.y = 78;
                doc.y += doc.text(bold, 16, hex("#22261d"), left, doc.y, tableWidth, report.title(), "left");
                doc.y += doc.text(regular, 9, hex("#5b6150"), left, doc.y, tableWidth, report.period(), "left");
                doc.moveDown(0.6f);

                List<SummaryItem> summary = report.summary() == null ? List.of() : report.summary();
                if (!summary.isEmpty()) {
                    float boxWidth = (tableWidth - (summary.size() - 1) * 8) / summary.size();
                    float top = doc.y;
                    for (int i = 0; i < summary.size(); i++) {
                        float x = left + i * (boxWidth + 8);
                        doc.fillRoundedRect(x, top, boxWidth, 40, 4, hex("#f2eedd"));
                        doc.textNoWrap(regular, 7.5f, hex("#5b6150"), x + 8, top + 7, boxWidth - 16, summary.get(i).label(), "left", false);
                        doc.textNoWrap(bold, 12, hex("#22261d"), x + 8, top + 20, boxWidth - 16, summary.get(i).value(), "left", false);
                    }
                    doc.y = top + 52;
                }

                List<Point> points = ReportBuilders.reportChartPoints(report, 12);
                if (report.chart() != null && !points.isEmpty()) {
                    doc.y += doc.text(bold, 10, hex("#22261d"), left, doc.y, tableWidth, report.chart().title(), "left");
                    doc.moveDown(0.3f);
                    double max = points.stream().mapToDouble(point -> Math.abs(point.value())).max().orElse(0);
                    float labelWidth = Math.min(160, tableWidth * 0.3f);
                    float valueWidth = 80;
                    float barSpace = tableWidth - labelWidth - valueWidth - 12;
                    for (Point point : points) {
                        float top = doc.y;
                        float barWidth = max > 0 ? (float) (Math.abs(point.value()) / max * barSpace) : 0;
                        doc.textNoWrap(regular, 8, hex("#22261d"), left, top + 1, labelWidth - 6, point.label(), "left", true);
                        doc.fillRoundedRect(left + labelWidth, top + 1, Math.max(2, barWidth), 9, 2,
                                point.value() < 0 ? hex("#D93C74") : hex("#C99A2E"));
                        String unit = report.chart().unit() != null ? " " + report.chart().unit() : "";
                        String value = Boolean.TRUE.equals(report.chart().money())
                                ? Money.formatEuro(point.value(), 0)
                                : Js.num(Math.round(point.value() * 10) / 10.0) + unit;
                        doc.textNoWrap(regular, 8, hex("#22261d"), left + labelWidth + barSpace + 12, top + 1, valueWidth, value, "right", false);
                        doc.y = top + 14;
                    }
                    doc.moveDown(0.8f);
                }

                drawRow(doc, report, widths, left, tableWidth, bottom,
                        report.columns().stream().map(Column::label).toList(), true, hex("#e7e2cc"));
                if (report.rows().isEmpty()) {
                    doc.moveDown(1);
                    doc.y += doc.text(regular, 10, hex("#5b6150"), left, doc.y, tableWidth, "There is no data for the selected period.", "left");
                }
                for (Map<String, Object> row : report.rows()) {
                    drawRow(doc, report, widths, left, tableWidth, bottom,
                            report.columns().stream().map(column -> cellText(column, row.get(column.key()))).toList(), false, null);
                }
                if (report.totals() != null && !report.rows().isEmpty()) {
                    List<String> cells = new ArrayList<>();
                    for (int i = 0; i < report.columns().size(); i++) {
                        Column column = report.columns().get(i);
                        cells.add(i == 0 ? "Total" : cellText(column, report.totals().get(column.key())));
                    }
                    drawRow(doc, report, widths, left, tableWidth, bottom, cells, true, hex("#f2eedd"));
                }
            }

            int count = document.getNumberOfPages();
            for (int i = 0; i < count; i++) {
                PDPage page = document.getPage(i);
                float pageWidth = page.getMediaBox().getWidth();
                try (PDPageContentStream footer = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String text = "FermApp, " + farmName + ", generated " + generated + "    Page " + (i + 1) + " of " + count;
                    float size = 7;
                    float textWidth = regular.getStringWidth(text) / 1000f * size;
                    float x = MARGIN + (pageWidth - MARGIN * 2) - textWidth;
                    // pdfkit placed the line box at pageHeight - 30 from the top, that is 30 pt above the bottom edge.
                    float baseline = 30 - regular.getFontDescriptor().getAscent() / 1000f * size;
                    footer.beginText();
                    footer.setFont(regular, size);
                    footer.setNonStrokingColor(hex("#5b6150"));
                    footer.newLineAtOffset(x, baseline);
                    footer.showText(text);
                    footer.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void drawRow(Canvas doc, ReportTable report, float[] widths, float left, float tableWidth, float bottom,
                                List<String> cells, boolean useBold, Color fill) throws IOException {
        PDFont font = doc.font(useBold);
        float size = 8;
        float rowHeight = 0;
        for (int i = 0; i < cells.size(); i++) {
            rowHeight = Math.max(rowHeight, doc.heightOf(font, size, cells.get(i), widths[i] - PADDING * 2));
        }
        rowHeight += PADDING * 2;
        if (doc.y + rowHeight > bottom) {
            doc.addPage();
            doc.y = MARGIN;
        }
        float top = doc.y;
        if (fill != null) {
            doc.fillRect(left, top, tableWidth, rowHeight, fill);
        }
        float x = left;
        for (int i = 0; i < cells.size(); i++) {
            doc.text(font, size, hex("#22261d"), x + PADDING, top + PADDING, widths[i] - PADDING * 2, cells.get(i),
                    report.columns().get(i).isNumeric() ? "right" : "left");
            x += widths[i];
        }
        doc.line(left, top + rowHeight, left + tableWidth, top + rowHeight, 0.4f, hex("#c9c4ad"));
        doc.y = top + rowHeight;
    }
}
