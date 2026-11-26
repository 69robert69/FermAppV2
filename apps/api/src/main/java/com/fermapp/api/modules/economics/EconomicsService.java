package com.fermapp.api.modules.economics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSettings;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSheet;
import com.fermapp.api.domain.economics.EconomicModels.FarmEconomics;
import com.fermapp.api.domain.economics.EconomicModels.FarmParcelEconomics;
import com.fermapp.api.domain.economics.EconomicModels.OperationInput;
import com.fermapp.api.domain.economics.EconomicModels.OperationMachine;
import com.fermapp.api.domain.economics.EconomicModels.OperationOperator;
import com.fermapp.api.domain.economics.EconomicModels.ParcelEconomicsResult;
import com.fermapp.api.domain.economics.EconomicModels.RecordedCost;
import com.fermapp.api.domain.economics.Economics;
import com.fermapp.api.domain.economics.Money;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.tillage.Tillage;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import com.fermapp.api.modules.work.RunProgress;
import com.fermapp.api.persistence.entity.CostEntry;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.EconomicDocument;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.WorkSession;
import com.fermapp.api.persistence.repository.CostEntryRepository;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.EconomicDocumentRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.persistence.repository.WorkSessionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Economic documents and the real cost and profit of every parcel (economics.service.ts). */
@Service
public class EconomicsService {

    public static final String SETTINGS_KEY = "settings";

    /** The farm's economics for a season: settings, one result per parcel and the summary. */
    public record FarmEconomicsResult(int year, EconomicSettings settings, List<FarmParcelEconomics> parcels, FarmEconomics summary) {
    }

    private final EconomicDocumentRepository documents;
    private final ParcelRepository parcels;
    private final CropSeasonRepository seasons;
    private final CostEntryRepository costs;
    private final WorkSessionRepository works;
    private final ObjectMapper mapper;

    public EconomicsService(EconomicDocumentRepository documents, ParcelRepository parcels, CropSeasonRepository seasons,
                            CostEntryRepository costs, WorkSessionRepository works, ObjectMapper mapper) {
        this.documents = documents;
        this.parcels = parcels;
        this.seasons = seasons;
        this.costs = costs;
        this.works = works;
        this.mapper = mapper;
    }

    public static String sheetKey(UUID parcelId, int year) {
        return "sheet:" + parcelId + ":" + year;
    }

    public EconomicSettings loadSettings(UUID farmId) {
        return Economics.parseSettings(documents.findByFarmIdAndKey(farmId, SETTINGS_KEY).map(EconomicDocument::getData).orElse(null));
    }

    @Transactional
    public EconomicSettings saveSettings(UUID farmId, EconomicSettings settings) {
        EconomicDocument document = documents.findByFarmIdAndKey(farmId, SETTINGS_KEY).orElseGet(() -> {
            EconomicDocument created = new EconomicDocument();
            created.setFarmId(farmId);
            created.setKey(SETTINGS_KEY);
            return created;
        });
        document.setData(mapper.valueToTree(settings));
        return Economics.parseSettings(documents.saveAndFlush(document).getData());
    }

    public Map<UUID, EconomicSheet> loadSheets(UUID farmId, int year) {
        Map<UUID, EconomicSheet> sheets = new LinkedHashMap<>();
        for (EconomicDocument document : documents.findSheets(farmId, year)) {
            if (document.getParcelId() != null) {
                sheets.put(document.getParcelId(), Economics.parseSheet(document.getData()));
            }
        }
        return sheets;
    }

    public EconomicSheet loadSheet(UUID farmId, UUID parcelId, int year) {
        return Economics.parseSheet(documents.findByFarmIdAndKey(farmId, sheetKey(parcelId, year)).map(EconomicDocument::getData).orElse(null));
    }

    @Transactional
    public EconomicSheet saveSheet(UUID farmId, UUID parcelId, int year, EconomicSheet sheet) {
        String key = sheetKey(parcelId, year);
        EconomicDocument document = documents.findByFarmIdAndKey(farmId, key).orElseGet(() -> {
            EconomicDocument created = new EconomicDocument();
            created.setFarmId(farmId);
            created.setKey(key);
            created.setParcelId(parcelId);
            created.setYear(year);
            return created;
        });
        document.setData(mapper.valueToTree(sheet));
        return Economics.parseSheet(documents.saveAndFlush(document).getData());
    }

    /**
     * Real cost and profit of every parcel in a season: the sheet's inputs, the work log priced
     * with the farm's diesel, machine and labour figures, the expenses recorded by category
     * and the operations still to come.
     */
    @Transactional(readOnly = true)
    public FarmEconomicsResult farmEconomics(UUID farmId, int year, UUID parcelId) {
        Instant start = Times.utc(year, 1, 1, 0);
        Instant end = Times.utc(year + 1, 1, 1, 0);
        List<Parcel> list = parcels.findByFarmIdAndArchivedAtIsNullOrderByNameAsc(farmId).stream()
                .filter(parcel -> parcelId == null || parcel.getId().equals(parcelId))
                .toList();
        List<UUID> ids = list.stream().map(Parcel::getId).toList();
        EconomicSettings settings = loadSettings(farmId);
        Map<UUID, EconomicSheet> sheets = loadSheets(farmId, year);
        Map<UUID, CropSeason> seasonByParcel = ids.isEmpty() ? Map.of() : seasons.findByParcelIdInAndYear(ids, year).stream()
                .collect(Collectors.toMap(CropSeason::getParcelId, Function.identity(), (a, b) -> a));
        Map<UUID, List<CostEntry>> costsByParcel = ids.isEmpty() ? Map.of() : costs.findByParcelIdInAndYear(ids, year).stream()
                .collect(Collectors.groupingBy(CostEntry::getParcelId));
        Map<UUID, List<WorkSession>> workByParcel = ids.isEmpty() ? Map.of()
                : works.findByParcelIdInAndStartedAtGreaterThanEqualAndStartedAtBefore(ids, start, end).stream()
                .collect(Collectors.groupingBy(WorkSession::getParcelId));
        Instant now = Times.now();

        List<FarmParcelEconomics> items = new ArrayList<>();
        for (Parcel parcel : list) {
            CropSeason season = seasonByParcel.get(parcel.getId());
            CropCode crop = season == null ? null : season.getCrop();
            List<OperationInput> operations = workByParcel.getOrDefault(parcel.getId(), List.of()).stream().map(work -> {
                double percent = RunProgress.of(work, now).percent();
                // Progress follows the clock, so the hours worked are the same share of the planned duration.
                return new OperationInput(work.getId().toString(), work.getType().name(), work.getStatus().name(), work.getAreaHa(),
                        percent, work.getDurationHours() * percent / 100, work.getFuelLiters(),
                        work.getMachine() == null ? null : new OperationMachine(work.getMachine().getId().toString(),
                                work.getMachine().getName(), work.getMachine().getType().name(), work.getMachine().getAvgConsumptionLh()),
                        new OperationOperator(work.getOperator().getId().toString(), work.getOperator().getFullName()),
                        Times.iso(work.getStartedAt()));
            }).toList();
            TillageSystem tillage = Tillage.parse(parcel.getTillageSystem());
            ParcelEconomicsResult result = Economics.computeParcelEconomics(parcel.getAreaHa(),
                    sheets.getOrDefault(parcel.getId(), Economics.parseSheet(null)), operations, settings, tillage, crop,
                    costsByParcel.getOrDefault(parcel.getId(), List.of()).stream()
                            .map(cost -> new RecordedCost(cost.getCategory(), Money.toEuro(cost.getAmount().doubleValue(), cost.getCurrency())))
                            .toList());
            items.add(new FarmParcelEconomics(parcel.getId().toString(), parcel.getName(), crop,
                    crop == null ? null : Crops.name(crop), tillage, result));
        }
        return new FarmEconomicsResult(year, settings, items, Economics.summarizeFarmEconomics(items));
    }
}
