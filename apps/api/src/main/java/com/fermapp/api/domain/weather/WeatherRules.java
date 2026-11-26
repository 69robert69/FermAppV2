package com.fermapp.api.domain.weather;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fermapp.api.domain.model.AlertSeverity;
import com.fermapp.api.domain.model.AlertType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Work windows (windows.ts) and weather alerts (alerts.ts). */
public final class WeatherRules {

    /**
     * One forecast hour. {@code time} is local time at the parcel: YYYY-MM-DDTHH:mm.
     * {@code weatherCode} is the WMO code, used for thunderstorms (95) and hail (96, 99).
     */
    public interface HourlyWeather {
        String time();

        double temperatureC();

        double humidityPct();

        double precipitationMm();

        Double precipitationProbabilityPct();

        double windKmh();

        Integer weatherCode();
    }

    /** The basic forecast hour, as produced by OpenWeatherMap. */
    public record ForecastHour(String time, double temperatureC, double humidityPct, double precipitationMm,
                               Double precipitationProbabilityPct, double windKmh, Integer weatherCode)
            implements HourlyWeather {
    }

    public enum FieldOperation { SPRAYING, FERTILIZING, SOWING, HARVESTING }

    public static final Map<FieldOperation, String> OPERATION_LABELS = new EnumMap<>(Map.of(
            FieldOperation.SPRAYING, "spraying",
            FieldOperation.FERTILIZING, "fertilizing",
            FieldOperation.SOWING, "sowing",
            FieldOperation.HARVESTING, "harvesting"));

    private record OperationRule(double maxWindKmh, double tempMin, double tempMax, double humidityMin, double humidityMax,
                                 double maxRainProbabilityPct, double maxHourlyRainMm, double maxRecentRainMm,
                                 int recentHours, double idealWindKmh, double idealTempC) {
    }

    private static final Map<FieldOperation, OperationRule> RULES = new EnumMap<>(Map.of(
            FieldOperation.SPRAYING, new OperationRule(15, 8, 25, 45, 95, 30, 0, 1, 3, 6, 16),
            FieldOperation.FERTILIZING, new OperationRule(25, 2, 28, 30, 100, 60, 1, 8, 6, 8, 14),
            FieldOperation.SOWING, new OperationRule(40, 5, 32, 25, 95, 40, 0.2, 6, 24, 10, 18),
            FieldOperation.HARVESTING, new OperationRule(45, 10, 38, 15, 72, 25, 0, 1, 12, 12, 26)));

    public record WorkWindow(FieldOperation operation, String start, String end, int hours, int score, String label) {
    }

    /** A drafted alert, before it is saved. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WeatherAlertDraft(AlertType type, AlertSeverity severity, String title, String message, String startsAt) {
    }

    private static final double RAIN_THRESHOLD_MM = 0.5;
    private static final double SPRAY_WIND_LIMIT_KMH = 15;
    private static final double FROST_C = 0;
    private static final double HEAT_C = 35;

    private WeatherRules() {
    }

    private static String dayName(String time) {
        LocalDate date = LocalDate.parse(time.substring(0, 10));
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static LocalDateTime parseLocal(String time) {
        return LocalDateTime.parse(time.length() > 16 ? time.substring(0, 16) : time);
    }

    private static String addHour(String time) {
        return parseLocal(time).plusHours(1).toString().substring(0, 16);
    }

    /** Intervals that cross midnight also show their end day. */
    private static String windowLabel(String start, String end) {
        String startClock = start.substring(11, 16);
        String endClock = end.substring(11, 16);
        Instant startAt = parseLocal(start).toInstant(ZoneOffset.UTC);
        Instant endAt = parseLocal(end).toInstant(ZoneOffset.UTC);
        double hoursLong = (endAt.toEpochMilli() - startAt.toEpochMilli()) / 3_600_000.0;
        boolean sameDay = start.substring(0, 10).equals(end.substring(0, 10));
        if (sameDay || ("00:00".equals(endClock) && hoursLong <= 24)) {
            return dayName(start) + " " + startClock + "-" + endClock;
        }
        return dayName(start) + " " + startClock + " - " + dayName(end) + " " + endClock;
    }

    private static double probability(HourlyWeather hour) {
        return hour.precipitationProbabilityPct() == null ? 0 : hour.precipitationProbabilityPct();
    }

    private static double hourScore(HourlyWeather hour, OperationRule rule) {
        double windPenalty = Math.max(0, hour.windKmh() - rule.idealWindKmh()) / Math.max(1, rule.maxWindKmh() - rule.idealWindKmh());
        double span = Math.max(rule.tempMax() - rule.idealTempC(), rule.idealTempC() - rule.tempMin());
        double tempPenalty = Math.abs(hour.temperatureC() - rule.idealTempC()) / span;
        double rainPenalty = probability(hour) / 100;
        return Math.max(0, 100 - windPenalty * 40 - tempPenalty * 35 - rainPenalty * 25);
    }

    private static boolean isSuitable(List<? extends HourlyWeather> hours, int index, OperationRule rule) {
        HourlyWeather hour = hours.get(index);
        if (hour.windKmh() > rule.maxWindKmh()) {
            return false;
        }
        if (hour.temperatureC() < rule.tempMin() || hour.temperatureC() > rule.tempMax()) {
            return false;
        }
        if (hour.humidityPct() < rule.humidityMin() || hour.humidityPct() > rule.humidityMax()) {
            return false;
        }
        if (hour.precipitationMm() > rule.maxHourlyRainMm()) {
            return false;
        }
        if (probability(hour) > rule.maxRainProbabilityPct()) {
            return false;
        }
        double recent = 0;
        for (int i = Math.max(0, index - rule.recentHours()); i < index; i++) {
            recent += hours.get(i).precipitationMm();
        }
        return recent <= rule.maxRecentRainMm();
    }

    /**
     * Finds suitable continuous intervals, ordered from best.
     * Hours before {@code from} only count towards recent rain.
     */
    public static List<WorkWindow> findWorkWindows(FieldOperation operation, List<? extends HourlyWeather> hours, int minHours, String from) {
        OperationRule rule = RULES.get(operation);
        List<WorkWindow> windows = new ArrayList<>();
        int startIndex = -1;
        for (int index = 0; index <= hours.size(); index++) {
            boolean suitable = index < hours.size()
                    && (from == null || hours.get(index).time().compareTo(from) >= 0)
                    && isSuitable(hours, index, rule);
            if (suitable && startIndex < 0) {
                startIndex = index;
            }
            if (!suitable && startIndex >= 0) {
                int count = index - startIndex;
                if (count >= minHours) {
                    List<? extends HourlyWeather> slice = hours.subList(startIndex, index);
                    double average = slice.stream().mapToDouble(hour -> hourScore(hour, rule)).sum() / count;
                    int durationBonus = Math.min(10, count);
                    String start = slice.get(0).time();
                    String end = addHour(slice.get(count - 1).time());
                    windows.add(new WorkWindow(operation, start, end, count,
                            (int) Math.min(100, Math.round(average * 0.9 + durationBonus)), windowLabel(start, end)));
                }
                startIndex = -1;
            }
        }
        windows.sort(Comparator.comparingInt(WorkWindow::score).reversed().thenComparing(WorkWindow::start));
        return windows;
    }

    public static String bestWindowMessage(FieldOperation operation, List<WorkWindow> windows) {
        String name = OPERATION_LABELS.get(operation);
        return windows.isEmpty()
                ? "No suitable window for " + name + " in the analysed period."
                : "Best window for " + name + ": " + windows.get(0).label();
    }

    private static String inHours(int n) {
        return n <= 1 ? "within the hour" : "in " + n + " hours";
    }

    private static int firstIndex(List<? extends HourlyWeather> window, Predicate<HourlyWeather> predicate) {
        for (int i = 0; i < window.size(); i++) {
            if (predicate.test(window.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean code(HourlyWeather hour, int value) {
        return hour.weatherCode() != null && hour.weatherCode() == value;
    }

    /** Generates at most one alert per type, for the first relevant moment in the next 24 hours. */
    public static List<WeatherAlertDraft> evaluateWeatherAlerts(String parcelName, List<? extends HourlyWeather> hours) {
        return evaluateWeatherAlerts(parcelName, hours, 24);
    }

    public static List<WeatherAlertDraft> evaluateWeatherAlerts(String parcelName, List<? extends HourlyWeather> hours, int horizonHours) {
        List<? extends HourlyWeather> window = hours.subList(0, Math.min(horizonHours, hours.size()));
        List<WeatherAlertDraft> alerts = new ArrayList<>();

        int hail = firstIndex(window, h -> code(h, 96) || code(h, 99));
        if (hail >= 0) {
            alerts.add(new WeatherAlertDraft(AlertType.HAIL, AlertSeverity.SEVERE, "Hail risk",
                    "Hailstorm expected " + inHours(hail) + " on parcel " + parcelName + ".", window.get(hail).time()));
        }
        int storm = firstIndex(window, h -> code(h, 95));
        if (storm >= 0) {
            alerts.add(new WeatherAlertDraft(AlertType.STORM, AlertSeverity.SEVERE, "Thunderstorm",
                    "Thunderstorm expected " + inHours(storm) + " on parcel " + parcelName + ".", window.get(storm).time()));
        }
        int rain = firstIndex(window, h -> h.precipitationMm() >= RAIN_THRESHOLD_MM);
        if (rain >= 0) {
            alerts.add(new WeatherAlertDraft(AlertType.RAIN, AlertSeverity.INFO, "Rain",
                    "Rain expected " + inHours(rain) + " on parcel " + parcelName + ".", window.get(rain).time()));
        }
        int frost = firstIndex(window, h -> h.temperatureC() <= FROST_C);
        if (frost >= 0) {
            alerts.add(new WeatherAlertDraft(AlertType.FROST, AlertSeverity.WARNING, "Frost",
                    "Frost expected " + inHours(frost) + " on parcel " + parcelName + ": "
                            + Math.round(window.get(frost).temperatureC()) + " °C.", window.get(frost).time()));
        }
        int heat = firstIndex(window, h -> h.temperatureC() >= HEAT_C);
        if (heat >= 0) {
            alerts.add(new WeatherAlertDraft(AlertType.HEAT, AlertSeverity.WARNING, "Extreme temperatures",
                    "Heatwave expected " + inHours(heat) + " on parcel " + parcelName + ": "
                            + Math.round(window.get(heat).temperatureC()) + " °C.", window.get(heat).time()));
        }
        if (!window.isEmpty() && window.get(0).windKmh() > SPRAY_WIND_LIMIT_KMH) {
            HourlyWeather current = window.get(0);
            alerts.add(new WeatherAlertDraft(AlertType.WIND_SPRAYING, AlertSeverity.WARNING, "Strong wind",
                    "Wind " + Math.round(current.windKmh()) + " km/h. Herbicide spraying is not recommended.", current.time()));
        }
        return alerts;
    }
}
