package com.fermapp.api.domain.report;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * A report table, shared by JSON, PDF and Excel output (reportBuilders.ts). Optional parts are
 * left out of the JSON when they are null, as the TypeScript objects left them undefined.
 *
 * @param rows each row maps a column key to a string, a number or null
 * @param orientation wide tables print in landscape; the user may still choose
 * @param summary headline figures printed above the table
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportTable(String title, String period, List<Column> columns, List<Map<String, Object>> rows,
                          Map<String, Object> totals, String orientation, Chart chart, List<SummaryItem> summary) {

    /**
     * A column. {@code width} is the relative width in PDF tables; {@code money} marks an amount in euro.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Column(String key, String label, double width, Boolean numeric, Boolean money) {

        public static Column text(String key, String label, double width) {
            return new Column(key, label, width, null, null);
        }

        public static Column number(String key, String label, double width) {
            return new Column(key, label, width, true, null);
        }

        public static Column money(String key, String label, double width) {
            return new Column(key, label, width, true, true);
        }

        @JsonIgnore
        public boolean isNumeric() {
            return Boolean.TRUE.equals(numeric);
        }

        @JsonIgnore
        public boolean isMoney() {
            return Boolean.TRUE.equals(money);
        }
    }

    /** Bars read from the columns {@code labelKey}/{@code valueKey} of each row, or from {@code points}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Chart(String title, String labelKey, String valueKey, List<Point> points, Boolean money, String unit) {
    }

    public record Point(String label, double value) {
    }

    public record SummaryItem(String label, String value) {
    }
}
