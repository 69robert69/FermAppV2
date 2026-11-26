package com.fermapp.api.modules.weather;

import com.fermapp.api.common.error.GlobalExceptionHandler;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.weather.WeatherRules;
import com.fermapp.api.domain.weather.WeatherRules.ForecastHour;
import com.fermapp.api.domain.weather.WeatherRules.WeatherAlertDraft;
import com.fermapp.api.integrations.OpenMeteoClient;
import com.fermapp.api.integrations.OpenMeteoClient.Forecast;
import com.fermapp.api.integrations.OpenWeatherClient;
import com.fermapp.api.modules.common.Views.AlertView;
import com.fermapp.api.modules.notifications.NotificationService;
import com.fermapp.api.modules.notifications.NotificationService.NotificationInput;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.WeatherAlert;
import com.fermapp.api.persistence.repository.WeatherAlertRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Weather alerts (weather.service.ts): Open-Meteo provides the hail codes, and OpenWeatherMap
 * takes priority for the other types when configured. The same alert is not repeated on the
 * same day for the same parcel.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private record SourcedAlert(WeatherAlertDraft alert, String source) {
    }

    private final OpenMeteoClient openMeteo;
    private final OpenWeatherClient openWeather;
    private final WeatherAlertRepository alerts;
    private final RealtimeHub realtime;
    private final NotificationService notifications;

    public WeatherService(OpenMeteoClient openMeteo, OpenWeatherClient openWeather, WeatherAlertRepository alerts,
                          RealtimeHub realtime, NotificationService notifications) {
        this.openMeteo = openMeteo;
        this.openWeather = openWeather;
        this.alerts = alerts;
        this.realtime = realtime;
        this.notifications = notifications;
    }

    private List<SourcedAlert> collect(Parcel parcel) {
        Map<String, SourcedAlert> byType = new LinkedHashMap<>();
        Forecast forecast = null;
        try {
            forecast = openMeteo.getForecast(parcel.getCenterLat(), parcel.getCenterLng());
        } catch (RuntimeException error) {
            log.warn("Open-Meteo unavailable for alerts: {}", error.getMessage());
        }
        if (forecast != null) {
            for (WeatherAlertDraft alert : WeatherRules.evaluateWeatherAlerts(parcel.getName(),
                    OpenMeteoClient.upcomingHours(forecast, Times.now()))) {
                byType.put(alert.type().name(), new SourcedAlert(alert, "open-meteo"));
            }
        }
        if (openWeather.isConfigured()) {
            List<ForecastHour> hours = null;
            try {
                hours = openWeather.getHours(parcel.getCenterLat(), parcel.getCenterLng());
            } catch (RuntimeException error) {
                log.warn("OpenWeatherMap unavailable for alerts: {}", error.getMessage());
            }
            if (hours != null) {
                for (WeatherAlertDraft alert : WeatherRules.evaluateWeatherAlerts(parcel.getName(), hours)) {
                    byType.put(alert.type().name(), new SourcedAlert(alert, "openweathermap"));
                }
            }
        }
        return new ArrayList<>(byType.values());
    }

    /** Saves the new alerts of the parcel, emits and notifies them, and returns the ones created now. */
    public List<AlertView> checkParcelAlerts(Parcel parcel) {
        List<AlertView> created = new ArrayList<>();
        for (SourcedAlert sourced : collect(parcel)) {
            WeatherAlertDraft alert = sourced.alert();
            String dedupeKey = alert.type().name() + ":" + Text.slice(alert.startsAt(), 10);
            if (alerts.existsByParcelIdAndDedupeKey(parcel.getId(), dedupeKey)) {
                continue;
            }
            WeatherAlert row = new WeatherAlert();
            row.setFarmId(parcel.getFarmId());
            row.setParcelId(parcel.getId());
            row.setType(alert.type());
            row.setSeverity(alert.severity());
            row.setTitle(alert.title());
            row.setMessage(alert.message());
            row.setStartsAt(alert.startsAt());
            row.setDedupeKey(dedupeKey);
            row.setSource(sourced.source());
            try {
                row = alerts.saveAndFlush(row);
            } catch (DataIntegrityViolationException error) {
                if (GlobalExceptionHandler.isUniqueViolation(error)) {
                    continue;
                }
                throw error;
            }
            AlertView view = AlertView.of(row);
            created.add(view);
            realtime.emitToFarm(parcel.getFarmId(), RealtimeEvent.ALERT_NEW, view);
            notifications.notify(new NotificationInput(parcel.getFarmId(), null, null, alert.title(), alert.message(),
                    Map.of("type", "alert", "id", row.getId().toString(), "parcelId", parcel.getId().toString())));
        }
        return created;
    }
}
