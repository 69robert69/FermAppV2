package com.fermapp.api.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.cache.CacheService;
import com.fermapp.api.common.http.UpstreamHttp;
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.domain.crops.CropRules;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Daily climate data from NASA POWER (integrations/nasaPower.ts). */
@Component
public class NasaPowerClient {

    private static final double FILL_VALUE = -999;

    public record PowerDay(String date, double tMeanC, double tMaxC, double tMinC, double precipitationMm, Double radiationMjM2) {
    }

    public record ClimateYear(int year, double precipitationMm, double seasonPrecipitationMm, double meanTempC,
                              double seasonTempC, double radiationMjM2, double gddBase10, int days) {
    }

    public record ClimateNormals(double seasonTempC, double annualPrecipitationMm, double gddBase10) {
    }

    private final UpstreamHttp http;
    private final CacheService cache;

    public NasaPowerClient(UpstreamHttp http, CacheService cache) {
        this.http = http;
        this.cache = cache;
    }

    private static String compact(String date) {
        return date.replace("-", "");
    }

    public List<PowerDay> getPowerDaily(double lat, double lng, String startDate, String endDate) {
        String key = "nasa:" + Js.toFixed(lat, 2) + ":" + Js.toFixed(lng, 2) + ":" + startDate + ":" + endDate;
        return cache.cached(key, 604_800, new TypeReference<List<PowerDay>>() { }, () -> {
            String url = UriComponentsBuilder.fromUriString("https://power.larc.nasa.gov/api/temporal/daily/point")
                    .queryParam("parameters", "T2M,T2M_MAX,T2M_MIN,PRECTOTCORR,ALLSKY_SFC_SW_DWN")
                    .queryParam("community", "AG")
                    .queryParam("latitude", Js.toFixed(lat, 4))
                    .queryParam("longitude", Js.toFixed(lng, 4))
                    .queryParam("start", compact(startDate))
                    .queryParam("end", compact(endDate))
                    .queryParam("format", "JSON")
                    .build().encode().toUriString();
            JsonNode parameter = http.fetchJson(UpstreamRequest.get("NASA POWER", url).timeout(Duration.ofSeconds(45)))
                    .path("properties").path("parameter");
            List<PowerDay> days = new ArrayList<>();
            Iterator<String> keys = parameter.path("T2M").fieldNames();
            while (keys.hasNext()) {
                String day = keys.next();
                Double tMean = value(parameter, "T2M", day);
                Double tMax = value(parameter, "T2M_MAX", day);
                Double tMin = value(parameter, "T2M_MIN", day);
                if (tMean == null || tMax == null || tMin == null) {
                    continue;
                }
                Double precipitation = value(parameter, "PRECTOTCORR", day);
                days.add(new PowerDay(day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6, 8),
                        tMean, tMax, tMin, precipitation == null ? 0 : precipitation, value(parameter, "ALLSKY_SFC_SW_DWN", day)));
            }
            return days;
        });
    }

    private static Double value(JsonNode parameter, String name, String key) {
        JsonNode node = parameter.path(name).get(key);
        if (node == null || !node.isNumber() || node.asDouble() == FILL_VALUE) {
            return null;
        }
        return node.asDouble();
    }

    private static boolean isSeason(String date) {
        int month = Integer.parseInt(date.substring(5, 7));
        return month >= 4 && month <= 9;
    }

    private static double sum(List<PowerDay> list, ToDoubleFunction<PowerDay> pick) {
        return list.stream().mapToDouble(pick).sum();
    }

    /** Aggregates by calendar year; the growing season is April-September. */
    public static List<ClimateYear> summarizeClimateYears(List<PowerDay> days) {
        Map<Integer, List<PowerDay>> byYear = new TreeMap<>();
        for (PowerDay day : days) {
            byYear.computeIfAbsent(Integer.parseInt(day.date().substring(0, 4)), year -> new ArrayList<>()).add(day);
        }
        List<ClimateYear> result = new ArrayList<>();
        byYear.forEach((year, items) -> {
            List<PowerDay> season = items.stream().filter(day -> isSeason(day.date())).toList();
            result.add(new ClimateYear(year,
                    Js.round1(sum(items, PowerDay::precipitationMm)),
                    Js.round1(sum(season, PowerDay::precipitationMm)),
                    Js.round1(sum(items, PowerDay::tMeanC) / items.size()),
                    season.isEmpty() ? 0 : Js.round1(sum(season, PowerDay::tMeanC) / season.size()),
                    Js.round1(sum(items, day -> day.radiationMjM2() == null ? 0 : day.radiationMjM2())),
                    Js.round(sum(items, day -> CropRules.dailyGdd(day.tMaxC(), day.tMinC(), 10))),
                    items.size()));
        });
        return result;
    }

    /** Average of the last complete years, used for crop recommendations; null when no year is complete. */
    public ClimateNormals getClimateNormals(double lat, double lng) {
        int years = 5;
        int lastYear = Instant.now().atZone(ZoneOffset.UTC).getYear() - 1;
        List<PowerDay> days = getPowerDaily(lat, lng, (lastYear - years + 1) + "-01-01", lastYear + "-12-31");
        List<ClimateYear> complete = summarizeClimateYears(days).stream().filter(year -> year.days() >= 360).toList();
        if (complete.isEmpty()) {
            return null;
        }
        double seasonTemp = complete.stream().mapToDouble(ClimateYear::seasonTempC).sum() / complete.size();
        double precipitation = complete.stream().mapToDouble(ClimateYear::precipitationMm).sum() / complete.size();
        double gdd = complete.stream().mapToDouble(ClimateYear::gddBase10).sum() / complete.size();
        return new ClimateNormals(Js.round1(seasonTemp), Js.round(precipitation), Js.round(gdd));
    }
}
