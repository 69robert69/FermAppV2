package com.fermapp.api.modules.weather;

import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.weather.WeatherRules;
import com.fermapp.api.domain.weather.WeatherRules.FieldOperation;
import com.fermapp.api.domain.weather.WeatherRules.WorkWindow;
import com.fermapp.api.integrations.OpenMeteoClient;
import com.fermapp.api.integrations.OpenMeteoClient.Forecast;
import com.fermapp.api.integrations.OpenMeteoClient.ForecastHourFull;
import com.fermapp.api.integrations.OpenMeteoClient.WeatherDay;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.AlertView;
import com.fermapp.api.modules.common.Views.AlertWithParcel;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.repository.WeatherAlertRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Forecast, weather history, work windows and alerts (weather.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Weather")
public class WeatherController {

    private static final List<FieldOperation> OPERATIONS =
            List.of(FieldOperation.SPRAYING, FieldOperation.FERTILIZING, FieldOperation.SOWING, FieldOperation.HARVESTING);

    public record ForecastResponse(String timezone, String range, ForecastHourFull current, List<ForecastHourFull> hours,
                                   List<WeatherDay> days) {
    }

    public record Totals(double precipitationMm, double et0Mm) {
    }

    public record HistoryResponse(String range, Totals totals, List<WeatherDay> days) {
    }

    public record OperationWindows(FieldOperation operation, String message, List<WorkWindow> windows) {
    }

    private final AccessGuard access;
    private final OpenMeteoClient openMeteo;
    private final WeatherAlertRepository alerts;
    private final WeatherService weather;

    public WeatherController(AccessGuard access, OpenMeteoClient openMeteo, WeatherAlertRepository alerts, WeatherService weather) {
        this.access = access;
        this.openMeteo = openMeteo;
        this.alerts = alerts;
        this.weather = weather;
    }

    @GetMapping("/parcels/{id}/weather/forecast")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Forecast for 24 hours, 7 or 14 days")
    public ResponseEntity<ApiResponse<ForecastResponse>> forecast(AuthContext auth, @PathVariable UUID id,
                                                                 @RequestParam(defaultValue = "7d") @Pattern(regexp = "24h|7d|14d") String range) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        Forecast forecast = openMeteo.getForecast(parcel.getCenterLat(), parcel.getCenterLng());
        Instant now = Times.now();
        List<ForecastHourFull> hours = OpenMeteoClient.upcomingHours(forecast, now);
        String today = OpenMeteoClient.localHour(forecast, now).substring(0, 10);
        List<WeatherDay> days = forecast.days().stream().filter(day -> day.date().compareTo(today) >= 0).toList();
        boolean dayRange = !"24h".equals(range);
        return ApiResponse.ok(new ForecastResponse(forecast.timezone(), range, hours.isEmpty() ? null : hours.get(0),
                dayRange ? List.of() : hours.subList(0, Math.min(24, hours.size())),
                dayRange ? days.subList(0, Math.min("7d".equals(range) ? 7 : 14, days.size())) : List.of()));
    }

    @GetMapping("/parcels/{id}/weather/history")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Weather history for 30 days or 12 months")
    public ResponseEntity<ApiResponse<HistoryResponse>> history(AuthContext auth, @PathVariable UUID id,
                                                               @RequestParam(defaultValue = "30d") @Pattern(regexp = "30d|12m") String range) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        ZonedDateTime end = Instant.now().minusMillis(2 * Times.DAY_MS).atZone(ZoneOffset.UTC);
        ZonedDateTime start = "30d".equals(range) ? end.minusDays(29) : end.minusYears(1);
        List<WeatherDay> days = openMeteo.getHistory(parcel.getCenterLat(), parcel.getCenterLng(),
                Times.isoDay(start.toInstant()), Times.isoDay(end.toInstant()));
        return ApiResponse.ok(new HistoryResponse(range, new Totals(
                Js.round1(days.stream().mapToDouble(WeatherDay::precipitationMm).sum()),
                Js.round1(days.stream().mapToDouble(day -> day.et0Mm() == null ? 0 : day.et0Mm()).sum())), days));
    }

    @GetMapping("/parcels/{id}/weather/windows")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Optimal windows for field work over the next 7 days")
    public ResponseEntity<ApiResponse<List<OperationWindows>>> windows(AuthContext auth, @PathVariable UUID id) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        Forecast forecast = openMeteo.getForecast(parcel.getCenterLat(), parcel.getCenterLng());
        Instant now = Times.now();
        String from = OpenMeteoClient.localHour(forecast, now);
        List<ForecastHourFull> upcoming = OpenMeteoClient.upcomingHours(forecast, now);
        String horizon = upcoming.size() > 168 ? upcoming.get(168).time() : null;
        List<ForecastHourFull> hours = forecast.hours().stream()
                .filter(hour -> horizon == null || hour.time().compareTo(horizon) < 0).toList();
        return ApiResponse.ok(OPERATIONS.stream().map(operation -> {
            List<WorkWindow> windows = WeatherRules.findWorkWindows(operation, hours, 2, from);
            return new OperationWindows(operation, WeatherRules.bestWindowMessage(operation, windows),
                    windows.subList(0, Math.min(5, windows.size())));
        }).toList());
    }

    @GetMapping("/alerts")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Farm weather alerts")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<AlertWithParcel>>> list(AuthContext auth,
                                                                  @RequestParam(required = false) UUID parcelId,
                                                                  @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        PageRequest page = PageRequest.of(0, limit);
        return ApiResponse.ok((parcelId == null ? alerts.findLatest(auth.farmId(), page)
                : alerts.findLatestForParcel(auth.farmId(), parcelId, page)).stream().map(AlertWithParcel::of).toList());
    }

    @PostMapping("/parcels/{id}/alerts/check")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Check weather alerts for the parcel now")
    public ResponseEntity<ApiResponse<List<AlertView>>> check(AuthContext auth, @PathVariable UUID id) {
        return ApiResponse.ok(weather.checkParcelAlerts(access.requireParcel(auth.farmId(), id)));
    }
}
