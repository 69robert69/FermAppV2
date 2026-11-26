package com.fermapp.api.modules.ai;

import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.crops.CropRules;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.economics.EconomicModels.CostTotals;
import com.fermapp.api.domain.economics.EconomicModels.EconomicCategory;
import com.fermapp.api.domain.economics.EconomicModels.FarmParcelEconomics;
import com.fermapp.api.domain.economics.Economics;
import com.fermapp.api.domain.economics.Money;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.SoilStatus;
import com.fermapp.api.domain.rotation.Rotation;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.domain.tillage.Tillage;
import com.fermapp.api.domain.tillage.Tillage.ParcelTillageEstimate;
import com.fermapp.api.domain.tillage.Tillage.TillageProfile;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import com.fermapp.api.integrations.OpenMeteoClient;
import com.fermapp.api.integrations.OpenMeteoClient.Forecast;
import com.fermapp.api.integrations.OpenMeteoClient.ForecastHourFull;
import com.fermapp.api.integrations.OpenMeteoClient.WeatherDay;
import com.fermapp.api.modules.economics.EconomicsService;
import com.fermapp.api.modules.economics.EconomicsService.FarmEconomicsResult;
import com.fermapp.api.modules.work.WorkViews;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.SatelliteObservation;
import com.fermapp.api.persistence.entity.SoilProfile;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.persistence.repository.SatelliteObservationRepository;
import com.fermapp.api.persistence.repository.SoilProfileRepository;
import com.fermapp.api.persistence.repository.WorkSessionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** The parcel data given to the assistant, read only from the user's farm (ai.context.ts). */
@Service
public class ParcelContextService {

    private static final Logger log = LoggerFactory.getLogger(ParcelContextService.class);

    public record ParcelInfo(String name, String code, double areaHa, String locality, String county) {
    }

    public record SeasonInfo(int year, String crop, Double yieldTHa) {
    }

    public record SoilInfo(String label, Double ph, Double organicMatterPct, Double clayPct, Double waterHoldingMmPerM) {
    }

    public record VegetationInfo(String date, double ndvi, Double ndre, int healthIndex, String label) {
    }

    public record WorkInfo(String date, String work, String status) {
    }

    public record RotationOption(String crop, int score, String verdict, String explanation) {
    }

    public record WeekWeather(double precipitationMm, double tMinC, double tMaxC, double maxWindKmh) {
    }

    public record TillageYear(int year, String system) {
    }

    public record TillageInfo(String system, int soilHealthScore, int waterRetentionStars, double estimatedCostPerHa,
                              double estimatedFuelLPerHa, List<String> discouragedOperations, List<String> advice,
                              List<TillageYear> history) {
    }

    public record EconomicsInfo(double costPerHaEur, double totalCostEur, Map<String, Long> shareByCategoryPct,
                                Double expectedProfitPerHaEur, Double actualProfitPerHaEur, List<String> findings) {
    }

    public record ParcelContext(ParcelInfo parcel, int year, String currentCrop, List<SeasonInfo> cropHistory, SoilInfo soil,
                                VegetationInfo vegetation, List<WorkInfo> recentWork, List<RotationOption> rotationNextYear,
                                WeekWeather weatherNext7Days, Double currentWindKmh, TillageInfo tillage, EconomicsInfo economics) {
    }

    private final ParcelRepository parcels;
    private final CropSeasonRepository seasons;
    private final SoilProfileRepository soilProfiles;
    private final SatelliteObservationRepository satellite;
    private final WorkSessionRepository works;
    private final OpenMeteoClient openMeteo;
    private final EconomicsService economics;

    public ParcelContextService(ParcelRepository parcels, CropSeasonRepository seasons, SoilProfileRepository soilProfiles,
                                SatelliteObservationRepository satellite, WorkSessionRepository works, OpenMeteoClient openMeteo,
                                EconomicsService economics) {
        this.parcels = parcels;
        this.seasons = seasons;
        this.soilProfiles = soilProfiles;
        this.satellite = satellite;
        this.works = works;
        this.openMeteo = openMeteo;
        this.economics = economics;
    }

    public ParcelContext build(UUID farmId, UUID parcelId) {
        Optional<Parcel> found = parcels.findByIdAndFarmId(parcelId, farmId);
        if (found.isEmpty()) {
            return null;
        }
        Parcel parcel = found.get();
        List<CropSeason> list = seasons.findTop10ByParcelIdOrderByYearDesc(parcelId);
        int year = Times.currentLocalYear();
        CropSeason current = list.stream().filter(season -> season.getYear() == year).findFirst().orElse(null);
        SoilProfile soil = soilProfiles.findByParcelId(parcelId).filter(profile -> profile.getStatus() == SoilStatus.READY).orElse(null);
        SatelliteObservation observation = satellite.findFirstByParcelIdOrderByDateDesc(parcelId).orElse(null);

        Forecast forecast = null;
        try {
            forecast = openMeteo.getForecast(parcel.getCenterLat(), parcel.getCenterLng());
        } catch (RuntimeException error) {
            log.warn("Forecast unavailable for the assistant: {}", error.getMessage());
        }
        List<WeatherDay> week = forecast == null ? List.of()
                : forecast.days().subList(Math.min(1, forecast.days().size()), Math.min(8, forecast.days().size()));
        List<CropHistoryEntry> history = list.stream().map(season -> new CropHistoryEntry(season.getCrop(), season.getYear())).toList();
        List<ForecastHourFull> upcoming = forecast == null ? List.of() : OpenMeteoClient.upcomingHours(forecast, Times.now());

        return new ParcelContext(
                new ParcelInfo(parcel.getName(), parcel.getCode(), Js.round2(parcel.getAreaHa()), parcel.getLocality(), parcel.getCounty()),
                year,
                current == null ? null : Crops.name(current.getCrop()),
                list.stream().map(season -> new SeasonInfo(season.getYear(), Crops.name(season.getCrop()),
                        season.getYieldT() != null ? Js.round2(season.getYieldT() / parcel.getAreaHa()) : null)).toList(),
                soil == null ? null : new SoilInfo(soil.getLabel(), soil.getPh(), soil.getOrganicMatterPct(), soil.getClayPct(),
                        soil.getWaterHoldingMmPerM()),
                observation == null ? null : new VegetationInfo(observation.getDate().toString(), observation.getNdvi(),
                        observation.getNdre(), observation.getHealthIndex(),
                        CropRules.vegetationHealth(observation.getNdvi(), observation.getNdre()).label()),
                works.findTop8ByParcelIdOrderByStartedAtDesc(parcelId).stream()
                        .map(work -> new WorkInfo(Times.isoDay(work.getStartedAt()), WorkViews.workLabel(work.getType()), work.getStatus().name()))
                        .toList(),
                Rotation.rankRotationOptions(year + 1, history, Crops.CROP_CODES).stream().limit(5)
                        .map(option -> new RotationOption(Crops.name(option.crop()), option.score(), option.verdict().name(), option.explanation()))
                        .toList(),
                week.isEmpty() ? null : new WeekWeather(
                        Js.round1(week.stream().mapToDouble(WeatherDay::precipitationMm).sum()),
                        week.stream().mapToDouble(WeatherDay::tMinC).min().orElse(0),
                        week.stream().mapToDouble(WeatherDay::tMaxC).max().orElse(0),
                        week.stream().mapToDouble(day -> day.windMaxKmh() == null ? 0 : day.windMaxKmh()).max().orElse(0)),
                forecast == null || upcoming.isEmpty() ? null : upcoming.get(0).windKmh(),
                tillageContext(parcel, current == null ? null : current.getCrop()),
                economicsContext(farmId, parcelId, year, parcel.getTillageSystem()));
    }

    private EconomicsInfo economicsContext(UUID farmId, UUID parcelId, int year, String tillage) {
        FarmEconomicsResult farm;
        try {
            farm = economics.farmEconomics(farmId, year, parcelId);
        } catch (RuntimeException error) {
            log.warn("Economics unavailable for the assistant: {}", error.getMessage());
            return null;
        }
        if (farm.parcels().isEmpty() || farm.parcels().get(0).result().season().total() <= 0) {
            return null;
        }
        FarmParcelEconomics item = farm.parcels().get(0);
        CostTotals season = item.result().season();
        Map<String, Long> shares = new LinkedHashMap<>();
        for (EconomicCategory category : EconomicCategory.values()) {
            double value = season.byCategory().get(category);
            if (value > 0) {
                shares.put(Economics.LABELS.get(category), Math.round(value / season.total() * 100));
            }
        }
        return new EconomicsInfo(season.perHa(), season.total(), shares,
                item.result().expected() == null ? null : item.result().expected().profitPerHa(),
                item.result().actual() == null ? null : item.result().actual().profitPerHa(),
                Economics.economicInsights(item.result(), Tillage.parse(tillage)).stream().map(insight -> insight.message()).toList());
    }

    private static TillageInfo tillageContext(Parcel parcel, CropCode crop) {
        TillageSystem system = Tillage.parse(parcel.getTillageSystem());
        if (system == null) {
            return null;
        }
        TillageProfile profile = Tillage.profile(system);
        ParcelTillageEstimate estimate = Tillage.estimateParcelTillage(system, parcel.getAreaHa());
        return new TillageInfo(profile.name(), estimate.soilHealth(), estimate.waterRetention(), estimate.costPerHa(),
                estimate.fuelPerHa(), new ArrayList<>(profile.discouragedWork().values()),
                Tillage.tillageRecommendations(system, crop).stream().map(Tillage.TillageAdvice::message).toList(),
                Tillage.parseTillageHistory(parcel.getTillageHistory()).stream()
                        .map(entry -> new TillageYear(entry.year(), Tillage.profile(entry.system()).name())).toList());
    }

    /** Answer computed from data, used when the AI model is not configured or does not respond. */
    public static String summarizeWithoutModel(ParcelContext context) {
        if (context == null) {
            return "Choose a parcel to get an analysis based on its history, soil and forecast.";
        }
        List<String> parts = new ArrayList<>();
        if (context.soil() != null && context.soil().ph() != null && context.soil().organicMatterPct() != null) {
            parts.add("Parcel " + context.parcel().name() + " has pH " + Js.num(context.soil().ph()) + " and "
                    + Js.num(context.soil().organicMatterPct()) + "% organic matter.");
        } else {
            parts.add("Parcel " + context.parcel().name() + " covers " + Js.num(context.parcel().areaHa())
                    + " ha, and its soil profile is not available yet.");
        }
        if (context.economics() != null) {
            String findings = context.economics().findings().stream().limit(2).collect(Collectors.joining(" "));
            parts.add(("The real production cost is " + Money.formatEuro(context.economics().costPerHaEur(), 2) + "/ha. " + findings).trim());
        }
        if (context.tillage() != null) {
            String advice = context.tillage().advice().isEmpty() ? "" : context.tillage().advice().get(0);
            parts.add(("The parcel is farmed with " + context.tillage().system() + " (soil health "
                    + context.tillage().soilHealthScore() + "/100). " + advice).trim());
        }
        Optional<SeasonInfo> last = context.cropHistory().stream().filter(season -> season.year() < context.year()).findFirst();
        if (context.currentCrop() != null) {
            parts.add("The " + context.year() + " crop is " + context.currentCrop().toLowerCase() + ".");
        } else if (last.isPresent()) {
            parts.add("The last recorded crop is " + last.get().crop().toLowerCase() + ", in " + last.get().year() + ".");
        }
        List<RotationOption> good = context.rotationNextYear().stream()
                .filter(option -> "RECOMMENDED".equals(option.verdict())).limit(2).toList();
        if (!good.isEmpty()) {
            parts.add("For next season, " + good.stream().map(option -> option.crop().toLowerCase()).collect(Collectors.joining(" or "))
                    + " is recommended.");
        }
        if (!context.rotationNextYear().isEmpty()) {
            RotationOption avoid = context.rotationNextYear().get(context.rotationNextYear().size() - 1);
            if ("NOT_RECOMMENDED".equals(avoid.verdict())) {
                parts.add(avoid.explanation());
            }
        }
        if (context.weatherNext7Days() != null) {
            parts.add("Over the next 7 days, " + Js.num(context.weatherNext7Days().precipitationMm())
                    + " mm of rainfall is expected, with temperatures between " + Math.round(context.weatherNext7Days().tMinC())
                    + " and " + Math.round(context.weatherNext7Days().tMaxC()) + " °C.");
        }
        if (context.vegetation() != null) {
            parts.add("The latest satellite image (" + context.vegetation().date() + ") shows NDVI " + Js.num(context.vegetation().ndvi())
                    + ": " + context.vegetation().label().toLowerCase() + ".");
        }
        return String.join(" ", parts);
    }
}
