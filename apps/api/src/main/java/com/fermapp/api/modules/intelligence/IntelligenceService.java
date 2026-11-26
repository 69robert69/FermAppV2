package com.fermapp.api.modules.intelligence;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.crops.CropRules;
import com.fermapp.api.domain.crops.CropRules.VegetationHealth;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.intelligence.Intelligence;
import com.fermapp.api.domain.intelligence.Intelligence.ActionPriority;
import com.fermapp.api.domain.intelligence.Intelligence.CostItem;
import com.fermapp.api.domain.intelligence.Intelligence.CostSummaryInput;
import com.fermapp.api.domain.intelligence.Intelligence.CropProgress;
import com.fermapp.api.domain.intelligence.Intelligence.NextAction;
import com.fermapp.api.domain.intelligence.Intelligence.NextActionInput;
import com.fermapp.api.domain.intelligence.Intelligence.ParcelEconomics;
import com.fermapp.api.domain.intelligence.Intelligence.RecentWork;
import com.fermapp.api.domain.intelligence.Intelligence.SprayAlert;
import com.fermapp.api.domain.intelligence.Intelligence.WeatherSummary;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.IotDeviceKind;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.domain.model.TaskStatus;
import com.fermapp.api.domain.rotation.Rotation;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.domain.rotation.Rotation.RotationResult;
import com.fermapp.api.integrations.OpenMeteoClient;
import com.fermapp.api.integrations.OpenMeteoClient.Forecast;
import com.fermapp.api.integrations.OpenMeteoClient.ForecastHourFull;
import com.fermapp.api.integrations.OpenMeteoClient.WeatherDay;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.MachineLocationView;
import com.fermapp.api.modules.common.Views.ObservationView;
import com.fermapp.api.modules.common.Views.PhotoView;
import com.fermapp.api.modules.common.Views.TaskWithAssignee;
import com.fermapp.api.modules.plans.PlanService;
import com.fermapp.api.modules.work.WorkService;
import com.fermapp.api.modules.work.WorkViews;
import com.fermapp.api.modules.work.WorkViews.IrrigationView;
import com.fermapp.api.modules.work.WorkViews.LabelledIrrigation;
import com.fermapp.api.modules.work.WorkViews.WorkView;
import com.fermapp.api.modules.work.WorkViews.WorkWithLocation;
import com.fermapp.api.persistence.entity.CostEntry;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Income;
import com.fermapp.api.persistence.entity.IotReading;
import com.fermapp.api.persistence.entity.IrrigationRun;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.ParcelPlan;
import com.fermapp.api.persistence.entity.SatelliteObservation;
import com.fermapp.api.persistence.entity.Task;
import com.fermapp.api.persistence.entity.WeatherData;
import com.fermapp.api.persistence.entity.WorkSession;
import com.fermapp.api.persistence.repository.CostEntryRepository;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.IncomeRepository;
import com.fermapp.api.persistence.repository.IotReadingRepository;
import com.fermapp.api.persistence.repository.IrrigationRunRepository;
import com.fermapp.api.persistence.repository.MachineLocationRepository;
import com.fermapp.api.persistence.repository.ObservationRepository;
import com.fermapp.api.persistence.repository.ParcelPlanRepository;
import com.fermapp.api.persistence.repository.PhotoRepository;
import com.fermapp.api.persistence.repository.SatelliteObservationRepository;
import com.fermapp.api.persistence.repository.SoilProfileRepository;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.persistence.repository.WeatherDataRepository;
import com.fermapp.api.persistence.repository.WorkSessionRepository;
import com.fermapp.api.security.AuthContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Everything the Parcel Intelligence panel shows (intelligence.service.ts). */
@Service
public class IntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceService.class);

    // ------------------------------------------------------------------ response shapes

    public record DayWithRain(String date, double tMaxC, double tMinC, Double tMeanC, double precipitationMm,
                              Double radiationMjM2, Double et0Mm, Double windMaxKmh, Double humidityPct,
                              Double rainProbabilityPct) {
        static DayWithRain of(WeatherDay day, Double rain) {
            return new DayWithRain(day.date(), day.tMaxC(), day.tMinC(), day.tMeanC(), day.precipitationMm(), day.radiationMjM2(),
                    day.et0Mm(), day.windMaxKmh(), day.humidityPct(), rain);
        }
    }

    public record ParcelWeather(String timezone, ForecastHourFull current, DayWithRain today, List<DayWithRain> next7,
                                List<DayWithRain> next14, List<SprayAlert> alerts) {
    }

    public record HistoryItem(int year, CropCode crop, String name) {
    }

    public record RotationScore(@JsonUnwrapped RotationResult result, String mode, List<HistoryItem> history) {
    }

    public record EconomicsView(int year, UUID seasonId, Double expectedYieldTHa, Double expectedPricePerT,
                                @JsonUnwrapped ParcelEconomics economics) {
    }

    public record NdviPoint(String date, double ndvi, Double ndre, int healthIndex, VegetationHealth health) {
    }

    public record NdviComparison(NdviPoint latest, NdviPoint sevenDaysAgo, NdviPoint thirtyDaysAgo) {
    }

    public record Header(UUID id, String name, String code, double areaHa, String county, String locality, double lat, double lng) {
    }

    public record CropPanel(UUID seasonId, CropCode code, String name, int year, Instant sowingDate,
                            @JsonUnwrapped CropProgress progress) {
    }

    public record IrrigationPanel(boolean configured, Double irrigatedHa, Double notIrrigatedHa, Long coveragePct) {
    }

    public record PlanPanel(UUID id, int year, Instant createdAt, JsonNode data) {
    }

    public record PlanHistoryItem(UUID id, int year, String cropName, String trigger, boolean active, Instant createdAt) {
    }

    public record TaskSummary(long todo, long inProgress, long done) {
    }

    public record TaskPanel(TaskSummary summary, List<TaskWithAssignee> items) {
    }

    public record IntelligenceData(String generatedAt, Header header, CropPanel crop, RotationScore rotation,
                                   IrrigationPanel irrigation, List<LabelledIrrigation> activeIrrigation,
                                   List<WorkWithLocation> activeWork, NextAction nextAction, PlanPanel plan,
                                   List<PlanHistoryItem> planHistory, TaskPanel tasks, ParcelWeather weather, ObjectNode soil,
                                   EconomicsView economics, NdviComparison satellite, List<PhotoView> photos,
                                   List<ObservationView> observations) {
    }

    private final AccessGuard access;
    private final OpenMeteoClient openMeteo;
    private final WeatherDataRepository weatherData;
    private final CropSeasonRepository seasons;
    private final CostEntryRepository costs;
    private final IncomeRepository incomes;
    private final SatelliteObservationRepository satellite;
    private final SoilProfileRepository soilProfiles;
    private final WorkSessionRepository works;
    private final IrrigationRunRepository runs;
    private final MachineLocationRepository locations;
    private final TaskRepository tasks;
    private final IotReadingRepository readings;
    private final PhotoRepository photos;
    private final ObservationRepository observations;
    private final ParcelPlanRepository plans;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public IntelligenceService(AccessGuard access, OpenMeteoClient openMeteo, WeatherDataRepository weatherData,
                               CropSeasonRepository seasons, CostEntryRepository costs, IncomeRepository incomes,
                               SatelliteObservationRepository satellite, SoilProfileRepository soilProfiles,
                               WorkSessionRepository works, IrrigationRunRepository runs, MachineLocationRepository locations,
                               TaskRepository tasks, IotReadingRepository readings, PhotoRepository photos,
                               ObservationRepository observations, ParcelPlanRepository plans, TransactionTemplate tx,
                               ObjectMapper mapper) {
        this.access = access;
        this.openMeteo = openMeteo;
        this.weatherData = weatherData;
        this.seasons = seasons;
        this.costs = costs;
        this.incomes = incomes;
        this.satellite = satellite;
        this.soilProfiles = soilProfiles;
        this.works = works;
        this.runs = runs;
        this.locations = locations;
        this.tasks = tasks;
        this.readings = readings;
        this.photos = photos;
        this.observations = observations;
        this.plans = plans;
        this.tx = tx;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ sections

    public ParcelWeather parcelWeather(Parcel parcel) {
        Forecast forecast = openMeteo.getForecast(parcel.getCenterLat(), parcel.getCenterLng());
        Instant now = Times.now();
        List<ForecastHourFull> hours = OpenMeteoClient.upcomingHours(forecast, now);
        String today = OpenMeteoClient.localHour(forecast, now).substring(0, 10);

        Map<String, Double> rainChance = new HashMap<>();
        for (ForecastHourFull hour : forecast.hours()) {
            String date = hour.time().substring(0, 10);
            double chance = hour.precipitationProbabilityPct() == null ? 0 : hour.precipitationProbabilityPct();
            rainChance.merge(date, chance, Math::max);
        }
        List<DayWithRain> days = forecast.days().stream()
                .filter(day -> day.date().compareTo(today) >= 0)
                .map(day -> DayWithRain.of(day, rainChance.get(day.date())))
                .toList();

        // Daily history is kept for analysis, and an error here does not affect the forecast.
        if (!days.isEmpty()) {
            DayWithRain current = days.get(0);
            try {
                LocalDate date = LocalDate.parse(current.date());
                WeatherData row = weatherData.findByParcelIdAndDate(parcel.getId(), date).orElseGet(() -> {
                    WeatherData created = new WeatherData();
                    created.setParcelId(parcel.getId());
                    created.setDate(date);
                    return created;
                });
                row.setTMaxC(current.tMaxC());
                row.setTMinC(current.tMinC());
                row.setPrecipitationMm(current.precipitationMm());
                row.setHumidityPct(current.humidityPct());
                row.setWindMaxKmh(current.windMaxKmh());
                row.setEt0Mm(current.et0Mm());
                weatherData.save(row);
            } catch (RuntimeException error) {
                log.warn("Saving weather failed: {}", error.getMessage());
            }
        }

        List<double[]> nextHours = hours.subList(0, Math.min(6, hours.size())).stream()
                .map(hour -> new double[] {hour.windKmh(), hour.precipitationProbabilityPct() == null ? 0 : hour.precipitationProbabilityPct()})
                .toList();
        return new ParcelWeather(forecast.timezone(), hours.isEmpty() ? null : hours.get(0), days.isEmpty() ? null : days.get(0),
                days.subList(Math.min(1, days.size()), Math.min(8, days.size())),
                days.subList(Math.min(1, days.size()), Math.min(15, days.size())),
                Intelligence.sprayingAlerts(nextHours));
    }

    public RotationScore rotationScore(UUID parcelId, Instant now) {
        List<CropSeason> list = seasons.findTop10ByParcelIdOrderByYearDesc(parcelId);
        List<CropHistoryEntry> history = list.stream().map(s -> new CropHistoryEntry(s.getCrop(), s.getYear())).toList();
        int year = Times.localYear(now);
        List<HistoryItem> items = list.stream().map(s -> new HistoryItem(s.getYear(), s.getCrop(), Crops.name(s.getCrop()))).toList();
        Optional<CropSeason> current = list.stream().filter(s -> s.getYear() == year).findFirst();
        if (current.isPresent()) {
            return new RotationScore(Rotation.evaluateRotation(current.get().getCrop(), year, history), "CURRENT", items);
        }
        RotationResult best = Rotation.rankRotationOptions(year, history, Crops.CROP_CODES).get(0);
        return new RotationScore(best, "SUGGESTED", items);
    }

    private static Double number(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    public EconomicsView economicsFor(Parcel parcel, Integer requestedYear) {
        int year = requestedYear != null ? requestedYear : Times.currentLocalYear();
        List<CostEntry> entries = costs.findByParcelIdAndYear(parcel.getId(), year);
        CropSeason season = seasons.findByParcelIdAndYear(parcel.getId(), year).orElse(null);
        List<Income> realized = incomes.findByParcelIdAndYear(parcel.getId(), year);
        Double expectedPrice = season == null ? null : number(season.getExpectedPricePerT());
        CostSummaryInput input = new CostSummaryInput(parcel.getAreaHa(),
                entries.stream().map(cost -> new CostItem(cost.getCategory(), cost.getAmount().doubleValue())).toList(),
                season == null ? null : season.getExpectedYieldTHa(), expectedPrice,
                season == null ? null : season.getYieldT(), season == null ? null : number(season.getActualPricePerT()));
        double recordedIncome = realized.stream().mapToDouble(income -> income.getAmount().doubleValue()).sum();
        return new EconomicsView(year, season == null ? null : season.getId(),
                season == null ? null : season.getExpectedYieldTHa(), expectedPrice,
                Intelligence.parcelEconomics(input, recordedIncome));
    }

    /** The observation closest to the target date, if one exists within the given tolerance. */
    private static SatelliteObservation nearest(List<SatelliteObservation> list, long target, int toleranceDays, UUID exclude) {
        SatelliteObservation best = null;
        long bestDistance = toleranceDays * Times.DAY_MS;
        for (SatelliteObservation item : list) {
            if (item.getId().equals(exclude)) {
                continue;
            }
            long distance = Math.abs(Times.atUtcMidnight(item.getDate()).toEpochMilli() - target);
            if (distance <= bestDistance) {
                best = item;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static NdviPoint point(SatelliteObservation item) {
        return item == null ? null : new NdviPoint(item.getDate().toString(), item.getNdvi(), item.getNdre(), item.getHealthIndex(),
                CropRules.vegetationHealth(item.getNdvi(), item.getNdre()));
    }

    public static NdviComparison ndviComparison(List<SatelliteObservation> list, Instant now) {
        SatelliteObservation latest = list.isEmpty() ? null : list.get(0);
        UUID exclude = latest == null ? null : latest.getId();
        return new NdviComparison(point(latest),
                point(nearest(list, now.toEpochMilli() - 7 * Times.DAY_MS, 4, exclude)),
                point(nearest(list, now.toEpochMilli() - 30 * Times.DAY_MS, 8, exclude)));
    }

    private Double latestSoilMoisture(UUID parcelId) {
        List<IotReading> found = readings.findLatestOfKind(parcelId, IotDeviceKind.SOIL_MOISTURE,
                Instant.now().minusMillis(2 * Times.DAY_MS), PageRequest.of(0, 1));
        return found.isEmpty() ? null : found.get(0).getValue();
    }

    /** For a planned crop, the next action is the first unfinished step of the plan. */
    private static NextAction nextPlanAction(JsonNode plan, List<Task> list, Instant now) {
        Optional<Task> next = list.stream()
                .filter(task -> task.getPlanStepKey() != null && task.getStatus() != TaskStatus.DONE)
                .min(Comparator.comparing(Task::getDueDate));
        if (next.isEmpty()) {
            return null;
        }
        String cropName = plan.path("cropName").asText("");
        return new NextAction("PLAN_STEP", next.get().getTitle(),
                "It is the next step in the technology plan for " + cropName.toLowerCase() + ".",
                ActionPriority.MEDIUM, ActionPriority.LOW, 80,
                (int) Math.max(0, Math.ceil((double) (next.get().getDueDate().toEpochMilli() - now.toEpochMilli()) / Times.DAY_MS)));
    }

    // ------------------------------------------------------------------ the whole panel

    public IntelligenceData buildIntelligence(AuthContext auth, UUID parcelId) {
        Parcel parcel = access.requireParcel(auth.farmId(), parcelId);
        Instant now = Times.now();
        int year = Times.localYear(now);

        ParcelWeather weather;
        try {
            weather = parcelWeather(parcel);
        } catch (RuntimeException error) {
            log.warn("Weather unavailable in the parcel panel: {}", error.getMessage());
            weather = null;
        }
        ParcelWeather finalWeather = weather;

        return tx.execute(status -> {
            CropSeason recorded = seasons.findByParcelIdAndYear(parcelId, year).orElse(null);
            List<SatelliteObservation> observationsList = satellite.findByParcelIdOrderByDateDesc(parcelId, PageRequest.of(0, 40));
            List<WorkSession> activeWork = works.findByParcelIdAndStatusInOrderByStartedAtDesc(parcelId, WorkService.ACTIVE);
            List<IrrigationRun> activeIrrigation = runs.findByParcelIdAndStatusInOrderByStartedAtAsc(parcelId, WorkService.ACTIVE);
            List<WorkSession> recentWork = works.findByParcelIdAndStatusAndStartedAtGreaterThanEqual(parcelId, RunStatus.COMPLETED,
                    now.minusMillis(60 * Times.DAY_MS));
            List<Task> taskList = tasks.findForParcel(parcelId, PageRequest.of(0, 50));
            Double soilMoisture = latestSoilMoisture(parcelId);
            List<ParcelPlan> planList = plans.findByParcelIdOrderByCreatedAtDesc(parcelId, PageRequest.of(0, 10));

            ParcelPlan activePlan = planList.stream().filter(item -> item.isActive() && item.getYear() >= year)
                    .min(Comparator.comparingInt(ParcelPlan::getYear)).orElse(null);
            JsonNode planData = activePlan == null ? null : activePlan.getData();

            // The last crop declared in the wizard is not the crop in the field when the plan starts in a future year.
            CropSeason season = recorded != null && activePlan != null && activePlan.getYear() > year && recorded.getSowingDate() == null
                    ? null : recorded;
            CropProgress progress = season == null ? null : Intelligence.cropProgress(season.getCrop(), season.getSowingDate(),
                    season.getHarvestDate(), season.getExpectedHarvestDate(), now);

            List<DayWithRain> week = finalWeather == null ? List.of() : finalWeather.next7();
            WeatherSummary weatherInput = week.isEmpty() ? null : new WeatherSummary(
                    week.stream().mapToDouble(day -> day.humidityPct() == null ? 0 : day.humidityPct()).sum() / week.size(),
                    week.stream().mapToDouble(day -> day.tMeanC() != null ? day.tMeanC() : (day.tMaxC() + day.tMinC()) / 2).sum() / week.size(),
                    week.stream().mapToDouble(DayWithRain::tMinC).min().orElse(0),
                    (int) week.stream().filter(day -> day.precipitationMm() >= 1).count(),
                    week.stream().mapToDouble(DayWithRain::precipitationMm).sum(),
                    week.stream().mapToDouble(day -> day.et0Mm() == null ? 0 : day.et0Mm()).sum());

            Double irrigated = parcel.getIrrigatedAreaHa() == null ? null : Math.min(parcel.getIrrigatedAreaHa(), parcel.getAreaHa());
            List<LabelledIrrigation> irrigationViews = new ArrayList<>();
            for (int i = 0; i < activeIrrigation.size(); i++) {
                IrrigationRun run = activeIrrigation.get(i);
                irrigationViews.add(new LabelledIrrigation(IrrigationView.of(run, now),
                        WorkViews.systemLabel(run.getSystemType()) + " #" + (i + 1)));
            }
            List<WorkWithLocation> workViews = activeWork.stream().map(work -> new WorkWithLocation(WorkView.of(work, now),
                    MachineLocationView.of(locations.findFirstByWorkSessionIdOrderByRecordedAtDesc(work.getId()).orElse(null)))).toList();

            NextAction planAction = season == null && planData != null ? nextPlanAction(planData, taskList, now) : null;
            NextAction nextAction = planAction != null ? planAction : Intelligence.nextRecommendedAction(new NextActionInput(
                    season == null ? null : season.getCrop(), progress, weatherInput, soilMoisture,
                    observationsList.isEmpty() ? null : observationsList.get(0).getHealthClass(),
                    recentWork.stream().map(work -> new RecentWork(work.getType().name(), work.getStartedAt())).toList(), now));

            long todo = taskList.stream().filter(task -> task.getStatus() == TaskStatus.TODO).count();
            long inProgress = taskList.stream().filter(task -> task.getStatus() == TaskStatus.IN_PROGRESS).count();
            long done = taskList.stream().filter(task -> task.getStatus() == TaskStatus.DONE).count();

            return new IntelligenceData(
                    Times.iso(now),
                    new Header(parcel.getId(), parcel.getName(), parcel.getCode(), Js.round2(parcel.getAreaHa()), parcel.getCounty(),
                            parcel.getLocality(), parcel.getCenterLat(), parcel.getCenterLng()),
                    season == null ? null : new CropPanel(season.getId(), season.getCrop(), Crops.name(season.getCrop()), season.getYear(),
                            season.getSowingDate(), progress),
                    rotationScore(parcel.getId(), now),
                    new IrrigationPanel(irrigated != null, irrigated == null ? null : Js.round2(irrigated),
                            irrigated == null ? null : Js.round2(parcel.getAreaHa() - irrigated),
                            irrigated == null ? null : Math.round(irrigated / parcel.getAreaHa() * 100)),
                    irrigationViews,
                    workViews,
                    nextAction,
                    activePlan == null ? null : new PlanPanel(activePlan.getId(), activePlan.getYear(), activePlan.getCreatedAt(),
                            PlanService.planForRole(planData, auth.role())),
                    planList.stream().map(item -> new PlanHistoryItem(item.getId(), item.getYear(), Crops.name(item.getCrop()),
                            item.getTrigger(), item.isActive(), item.getCreatedAt())).toList(),
                    new TaskPanel(new TaskSummary(todo, inProgress, done), taskList.stream().map(TaskWithAssignee::of).toList()),
                    finalWeather,
                    SoilSection.of(soilProfiles.findByParcelId(parcelId).orElse(null), mapper),
                    // Financial data is not sent to operators.
                    auth.role() == Role.OPERATOR ? null : economicsFor(parcel, year),
                    ndviComparison(observationsList, now),
                    photos.findByParcelIdOrderByCreatedAtDesc(parcelId, PageRequest.of(0, 12)).stream().map(PhotoView::of).toList(),
                    observations.findLatest(parcelId, PageRequest.of(0, 10)).stream().map(ObservationView::of).toList());
        });
    }
}
