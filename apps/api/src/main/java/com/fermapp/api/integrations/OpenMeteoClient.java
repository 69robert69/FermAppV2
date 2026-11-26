package com.fermapp.api.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.cache.CacheService;
import com.fermapp.api.common.http.UpstreamHttp;
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.weather.WeatherRules.HourlyWeather;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Forecast and weather history from Open-Meteo (integrations/openMeteo.ts). */
@Component
public class OpenMeteoClient {

    private static final String SERVICE = "Open-Meteo";
    private static final String HOURLY = String.join(",", "temperature_2m", "relative_humidity_2m", "precipitation",
            "precipitation_probability", "wind_speed_10m", "shortwave_radiation", "evapotranspiration",
            "et0_fao_evapotranspiration", "weather_code");
    private static final String DAILY = String.join(",", "temperature_2m_max", "temperature_2m_min", "temperature_2m_mean",
            "precipitation_sum", "shortwave_radiation_sum", "et0_fao_evapotranspiration", "wind_speed_10m_max",
            "relative_humidity_2m_mean");

    /** One forecast hour, local time at the parcel. */
    public record ForecastHourFull(String time, double temperatureC, double humidityPct, double precipitationMm,
                                   Double precipitationProbabilityPct, double windKmh, Integer weatherCode,
                                   Double radiationWm2, Double evapotranspirationMm, Double et0Mm) implements HourlyWeather {
    }

    public record WeatherDay(String date, double tMaxC, double tMinC, Double tMeanC, double precipitationMm,
                             Double radiationMjM2, Double et0Mm, Double windMaxKmh, Double humidityPct) {
    }

    public record Forecast(String timezone, int utcOffsetSeconds, List<ForecastHourFull> hours, List<WeatherDay> days) {
    }

    private final UpstreamHttp http;
    private final CacheService cache;

    public OpenMeteoClient(UpstreamHttp http, CacheService cache) {
        this.http = http;
        this.cache = cache;
    }

    private static String coord(double value) {
        return Js.toFixed(value, 2);
    }

    private static Double at(JsonNode series, String name, int index) {
        JsonNode values = series.get(name);
        if (values == null || !values.isArray() || index >= values.size() || values.get(index).isNull()) {
            return null;
        }
        return values.get(index).asDouble();
    }

    private static List<WeatherDay> mapDays(JsonNode daily) {
        List<WeatherDay> days = new ArrayList<>();
        JsonNode time = daily.path("time");
        for (int i = 0; i < time.size(); i++) {
            Double tMax = at(daily, "temperature_2m_max", i);
            Double tMin = at(daily, "temperature_2m_min", i);
            if (tMax == null || tMin == null) {
                continue;
            }
            Double precipitation = at(daily, "precipitation_sum", i);
            days.add(new WeatherDay(time.get(i).asText(), tMax, tMin, at(daily, "temperature_2m_mean", i),
                    precipitation == null ? 0 : precipitation, at(daily, "shortwave_radiation_sum", i),
                    at(daily, "et0_fao_evapotranspiration", i), at(daily, "wind_speed_10m_max", i),
                    at(daily, "relative_humidity_2m_mean", i)));
        }
        return days;
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }

    public Forecast getForecast(double lat, double lng) {
        return cache.cached("weather:forecast:" + coord(lat) + ":" + coord(lng), 1800, Forecast.class, () -> {
            String url = UriComponentsBuilder.fromUriString("https://api.open-meteo.com/v1/forecast")
                    .queryParam("latitude", coord(lat))
                    .queryParam("longitude", coord(lng))
                    .queryParam("hourly", HOURLY)
                    .queryParam("daily", DAILY)
                    // The current day plus the next 14 full days.
                    .queryParam("forecast_days", "15")
                    .queryParam("past_days", "1")
                    .queryParam("timezone", "auto")
                    .queryParam("wind_speed_unit", "kmh")
                    .build().encode().toUriString();
            JsonNode data = http.fetchJson(UpstreamRequest.get(SERVICE, url));
            JsonNode hourly = data.path("hourly");
            List<ForecastHourFull> hours = new ArrayList<>();
            JsonNode time = hourly.path("time");
            for (int i = 0; i < time.size(); i++) {
                Double temperature = at(hourly, "temperature_2m", i);
                if (temperature == null) {
                    continue;
                }
                Double code = at(hourly, "weather_code", i);
                hours.add(new ForecastHourFull(time.get(i).asText(), temperature, orZero(at(hourly, "relative_humidity_2m", i)),
                        orZero(at(hourly, "precipitation", i)), at(hourly, "precipitation_probability", i),
                        orZero(at(hourly, "wind_speed_10m", i)), code == null ? null : code.intValue(),
                        at(hourly, "shortwave_radiation", i), at(hourly, "evapotranspiration", i),
                        at(hourly, "et0_fao_evapotranspiration", i)));
            }
            return new Forecast(data.path("timezone").asText(), data.path("utc_offset_seconds").asInt(), hours,
                    mapDays(data.path("daily")));
        });
    }

    /** Current hour in the parcel's time zone, in the format used by the forecast. */
    public static String localHour(Forecast forecast, Instant now) {
        return Times.iso(now.plusSeconds(forecast.utcOffsetSeconds())).substring(0, 13) + ":00";
    }

    public static List<ForecastHourFull> upcomingHours(Forecast forecast, Instant now) {
        String current = localHour(forecast, now);
        return forecast.hours().stream().filter(hour -> hour.time().compareTo(current) >= 0).toList();
    }

    public List<WeatherDay> getHistory(double lat, double lng, String startDate, String endDate) {
        String key = "weather:history:" + coord(lat) + ":" + coord(lng) + ":" + startDate + ":" + endDate;
        return cache.cached(key, 43_200, new com.fasterxml.jackson.core.type.TypeReference<List<WeatherDay>>() { }, () -> {
            String url = UriComponentsBuilder.fromUriString("https://archive-api.open-meteo.com/v1/archive")
                    .queryParam("latitude", coord(lat))
                    .queryParam("longitude", coord(lng))
                    .queryParam("start_date", startDate)
                    .queryParam("end_date", endDate)
                    .queryParam("daily", DAILY)
                    .queryParam("timezone", "auto")
                    .queryParam("wind_speed_unit", "kmh")
                    .build().encode().toUriString();
            JsonNode data = http.fetchJson(UpstreamRequest.get(SERVICE, url).timeout(Duration.ofSeconds(25)));
            return mapDays(data.path("daily"));
        });
    }
}
