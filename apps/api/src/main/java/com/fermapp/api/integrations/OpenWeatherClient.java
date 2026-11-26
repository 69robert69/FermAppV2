package com.fermapp.api.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.cache.CacheService;
import com.fermapp.api.common.http.UpstreamHttp;
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.domain.weather.WeatherRules.ForecastHour;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** The OpenWeatherMap 5-day forecast, used for weather alerts when a key is configured. */
@Component
public class OpenWeatherClient {

    private static final long HOUR_S = 3600;

    private final UpstreamHttp http;
    private final CacheService cache;
    private final AppProperties properties;

    public OpenWeatherClient(UpstreamHttp http, CacheService cache, AppProperties properties) {
        this.http = http;
        this.cache = cache;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.hasOpenWeather();
    }

    /** OpenWeatherMap codes: group 2xx is thunderstorm; 906 is hail. */
    private static Integer toWmoCode(JsonNode weather) {
        if (weather == null || !weather.isArray() || weather.isEmpty() || !weather.get(0).has("id")) {
            return null;
        }
        int id = weather.get(0).path("id").asInt();
        if (id == 906) {
            return 96;
        }
        if (id >= 200 && id < 300) {
            return 95;
        }
        return null;
    }

    private static String localTime(long timestampS, long timezoneOffsetS) {
        return Times.iso(Instant.ofEpochSecond(timestampS + timezoneOffsetS)).substring(0, 16);
    }

    /** Converts the 3-hour steps into hours, starting from the current hour, so the "in N hours" message is exact. */
    public static List<ForecastHour> expandOwmForecast(JsonNode list, long timezoneOffsetS, long nowMs) {
        List<ForecastHour> hours = new ArrayList<>();
        long currentHourS = Math.floorDiv(nowMs / 1000, HOUR_S) * HOUR_S;
        for (JsonNode entry : list) {
            double precipitation = (entry.path("rain").path("3h").asDouble(0) + entry.path("snow").path("3h").asDouble(0)) / 3;
            long dt = entry.path("dt").asLong();
            for (int step = 0; step < 3; step++) {
                long timestamp = dt + step * HOUR_S;
                if (timestamp < currentHourS) {
                    continue;
                }
                hours.add(new ForecastHour(localTime(timestamp, timezoneOffsetS), entry.path("main").path("temp").asDouble(),
                        entry.path("main").path("humidity").asDouble(), precipitation,
                        (double) Math.round(entry.path("pop").asDouble(0) * 100),
                        entry.path("wind").path("speed").asDouble() * 3.6, toWmoCode(entry.get("weather"))));
            }
        }
        // The first step may start in the future; the missing hours are filled in without rain.
        if (!list.isEmpty() && list.get(0).path("dt").asLong() > currentHourS) {
            JsonNode first = list.get(0);
            long missing = Math.round((first.path("dt").asLong() - currentHourS) / (double) HOUR_S);
            List<ForecastHour> padding = new ArrayList<>();
            for (int i = 0; i < missing; i++) {
                padding.add(new ForecastHour(localTime(currentHourS + i * HOUR_S, timezoneOffsetS),
                        first.path("main").path("temp").asDouble(), first.path("main").path("humidity").asDouble(), 0, 0.0,
                        first.path("wind").path("speed").asDouble() * 3.6, null));
            }
            hours.addAll(0, padding);
        }
        return hours;
    }

    public List<ForecastHour> getHours(double lat, double lng) {
        String key = "owm:" + Js.toFixed(lat, 2) + ":" + Js.toFixed(lng, 2);
        return cache.cached(key, 1800, new TypeReference<List<ForecastHour>>() { }, () -> {
            String url = UriComponentsBuilder.fromUriString("https://api.openweathermap.org/data/2.5/forecast")
                    .queryParam("lat", Js.toFixed(lat, 4))
                    .queryParam("lon", Js.toFixed(lng, 4))
                    .queryParam("units", "metric")
                    .queryParam("appid", properties.openweatherApiKey() == null ? "" : properties.openweatherApiKey())
                    .build().encode().toUriString();
            JsonNode data = http.fetchJson(UpstreamRequest.get("OpenWeatherMap", url));
            return expandOwmForecast(data.path("list"), data.path("city").path("timezone").asLong(), System.currentTimeMillis());
        });
    }
}
