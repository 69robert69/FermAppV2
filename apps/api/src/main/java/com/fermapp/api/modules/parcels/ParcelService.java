package com.fermapp.api.modules.parcels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.geo.Geo;
import com.fermapp.api.domain.geo.Geo.GeoMetrics;
import com.fermapp.api.domain.geo.Geo.Polygon;
import com.fermapp.api.domain.tillage.Tillage;
import com.fermapp.api.domain.tillage.Tillage.TillageHistoryEntry;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.CropSeasonView;
import com.fermapp.api.modules.common.Views.ParcelView;
import com.fermapp.api.modules.common.Views.SatelliteView;
import com.fermapp.api.modules.common.Views.SoilProfileView;
import com.fermapp.api.modules.parcels.GeoIo.ExportFile;
import com.fermapp.api.modules.parcels.GeoIo.ExportParcel;
import com.fermapp.api.modules.parcels.GeoIo.ImportResult;
import com.fermapp.api.modules.parcels.ParcelDtos.ImportPreview;
import com.fermapp.api.modules.parcels.ParcelDtos.ImportResponse;
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDetail;
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDraft;
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelListItem;
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelUpdate;
import com.fermapp.api.modules.parcels.ParcelDtos.SatelliteBrief;
import com.fermapp.api.modules.parcels.ParcelDtos.SeasonBrief;
import com.fermapp.api.modules.parcels.ParcelDtos.SoilBrief;
import com.fermapp.api.modules.soil.SoilService;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.SatelliteObservation;
import com.fermapp.api.persistence.entity.SoilProfile;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.persistence.repository.SatelliteObservationRepository;
import com.fermapp.api.persistence.repository.SoilProfileRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Parcels: listing, creation, import and export, outline changes and tillage history (parcels.routes.ts). */
@Service
public class ParcelService {

    private final ParcelRepository parcels;
    private final CropSeasonRepository seasons;
    private final SoilProfileRepository soilProfiles;
    private final SatelliteObservationRepository satellite;
    private final SoilService soil;
    private final GeoIo geoIo;
    private final AccessGuard access;
    private final ObjectMapper mapper;

    public ParcelService(ParcelRepository parcels, CropSeasonRepository seasons, SoilProfileRepository soilProfiles,
                         SatelliteObservationRepository satellite, SoilService soil, GeoIo geoIo, AccessGuard access,
                         ObjectMapper mapper) {
        this.parcels = parcels;
        this.seasons = seasons;
        this.soilProfiles = soilProfiles;
        this.satellite = satellite;
        this.soil = soil;
        this.geoIo = geoIo;
        this.access = access;
        this.mapper = mapper;
    }

    private String nextCode(UUID farmId) {
        return "P-" + Text.padNumber(parcels.countByFarmId(farmId) + 1, 4);
    }

    /** Area, perimeter and centre, computed from the outline. */
    private void applyGeometry(Parcel parcel, Polygon geometry) {
        GeoMetrics metrics = Geo.computeGeoMetrics(geometry);
        parcel.setGeometry(mapper.valueToTree(geometry));
        parcel.setAreaHa(metrics.areaHa());
        parcel.setPerimeterM(metrics.perimeterM());
        parcel.setCenterLat(metrics.center().lat());
        parcel.setCenterLng(metrics.center().lng());
    }

    /** The data required to create a parcel; the technology chosen at creation is also the first entry of its history. */
    public Parcel newParcel(UUID farmId, ParcelDraft draft) {
        Parcel parcel = new Parcel();
        parcel.setFarmId(farmId);
        parcel.setName(draft.name());
        parcel.setCode(draft.code() != null ? draft.code() : nextCode(farmId));
        parcel.setLocality(draft.locality());
        parcel.setCounty(draft.county());
        parcel.setNotes(draft.notes());
        parcel.setIrrigatedAreaHa(draft.irrigatedAreaHa());
        parcel.setTillageSystem(draft.tillageSystem() == null ? null : draft.tillageSystem().name());
        if (draft.tillageSystem() != null) {
            parcel.setTillageHistory(mapper.valueToTree(List.of(new TillageHistoryEntry(Times.currentLocalYear(), draft.tillageSystem()))));
        }
        applyGeometry(parcel, draft.geometry().toPolygon());
        return parcel;
    }

    @Transactional(readOnly = true)
    public List<ParcelListItem> list(UUID farmId, boolean archived, String search) {
        int year = Times.currentLocalYear();
        List<Parcel> found = parcels.search(farmId, archived, Text.containsPattern(search));
        List<UUID> ids = found.stream().map(Parcel::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, CropSeason> current = seasons.findByParcelIdInAndYear(ids, year).stream()
                .collect(Collectors.toMap(CropSeason::getParcelId, Function.identity(), (a, b) -> a));
        Map<UUID, SoilProfile> soils = soilProfiles.findByParcelIdIn(ids).stream()
                .collect(Collectors.toMap(SoilProfile::getParcelId, Function.identity(), (a, b) -> a));
        Map<UUID, SatelliteObservation> latest = satellite.findLatestFor(ids).stream()
                .collect(Collectors.toMap(SatelliteObservation::getParcelId, Function.identity(), (a, b) -> a));
        return found.stream().map(parcel -> {
            CropSeason season = current.get(parcel.getId());
            SoilProfile profile = soils.get(parcel.getId());
            SatelliteObservation observation = latest.get(parcel.getId());
            return new ParcelListItem(ParcelView.of(parcel),
                    season == null ? List.of() : List.of(new SeasonBrief(season.getCrop(), season.getYear())),
                    profile == null ? null : new SoilBrief(profile.getStatus(), profile.getLabel()),
                    observation == null ? List.of() : List.of(new SatelliteBrief(Times.atUtcMidnight(observation.getDate()),
                            observation.getNdvi(), observation.getHealthIndex(), observation.getHealthClass())));
        }).toList();
    }

    public ParcelView create(UUID farmId, ParcelDraft draft) {
        Parcel parcel = parcels.saveAndFlush(newParcel(farmId, draft));
        soil.loadInBackground(parcel);
        return ParcelView.of(parcel);
    }

    public ImportResponse importFile(UUID farmId, String format, boolean dryRun, byte[] content) {
        ImportResult result = geoIo.parseGeoFile(format, content);
        List<ImportPreview> preview = result.parcels().stream()
                .map(parcel -> new ImportPreview(parcel.name(), parcel.code(), Geo.computeGeoMetrics(parcel.geometry()).areaHa()))
                .toList();
        if (dryRun) {
            return new ImportResponse(true, preview, result.problems(), 0);
        }
        List<Parcel> existing = parcels.findByFarmId(farmId);
        Set<String> usedCodes = existing.stream().map(Parcel::getCode).collect(Collectors.toCollection(HashSet::new));
        long[] sequence = {existing.size()};
        Function<String, String> assignCode = preferred -> {
            if (preferred != null && !usedCodes.contains(preferred)) {
                usedCodes.add(preferred);
                return preferred;
            }
            String code;
            do {
                sequence[0] += 1;
                code = "P-" + Text.padNumber(sequence[0], 4);
            } while (usedCodes.contains(code));
            usedCodes.add(code);
            return code;
        };
        List<Parcel> created = new ArrayList<>();
        for (GeoIo.ImportedParcel imported : result.parcels()) {
            Parcel parcel = new Parcel();
            parcel.setFarmId(farmId);
            parcel.setName(Text.slice(imported.name(), 120));
            parcel.setCode(assignCode.apply(imported.code()));
            parcel.setLocality(imported.locality());
            parcel.setCounty(imported.county());
            parcel.setNotes(imported.notes());
            applyGeometry(parcel, imported.geometry());
            created.add(parcel);
        }
        List<Parcel> saved = parcels.saveAllAndFlush(created);
        saved.forEach(soil::loadInBackground);
        return new ImportResponse(false, preview, result.problems(), saved.size());
    }

    public ExportFile export(UUID farmId, String format) {
        List<ExportParcel> list = parcels.findByFarmIdAndArchivedAtIsNullOrderByNameAsc(farmId).stream()
                .map(parcel -> new ExportParcel(parcel.getName(), parcel.getCode(), parcel.getAreaHa(), parcel.getPerimeterM(),
                        parcel.getLocality(), parcel.getCounty(), parcel.getNotes(),
                        mapper.convertValue(parcel.getGeometry(), Polygon.class)))
                .toList();
        return geoIo.exportParcels(format, list);
    }

    @Transactional(readOnly = true)
    public ParcelDetail detail(UUID farmId, UUID id) {
        Parcel parcel = access.requireParcel(farmId, id);
        return new ParcelDetail(ParcelView.of(parcel),
                seasons.findTop10ByParcelIdOrderByYearDesc(id).stream().map(CropSeasonView::raw).toList(),
                SoilProfileView.of(soilProfiles.findByParcelId(id).orElse(null)),
                satellite.findByParcelIdOrderByDateDesc(id, PageRequest.of(0, 1)).stream().map(SatelliteView::of).toList());
    }

    public ParcelView update(UUID farmId, UUID id, ParcelUpdate body) {
        Parcel parcel = access.requireParcel(farmId, id);
        body.name().ifPresent(parcel::setName);
        body.code().ifPresent(parcel::setCode);
        body.locality().ifPresent(parcel::setLocality);
        body.county().ifPresent(parcel::setCounty);
        body.notes().ifPresent(parcel::setNotes);
        body.irrigatedAreaHa().ifPresent(parcel::setIrrigatedAreaHa);
        boolean redrawn = body.geometry().isPresent();
        if (redrawn) {
            applyGeometry(parcel, body.geometry().get().toPolygon());
        }
        Parcel saved = parcels.saveAndFlush(parcel);
        if (redrawn) {
            soil.loadInBackground(saved);
        }
        return ParcelView.of(saved);
    }

    /** Sets the technology of a year; the current technology follows the history. */
    public ParcelView setTillage(UUID farmId, UUID id, TillageSystem system, Integer year) {
        Parcel parcel = access.requireParcel(farmId, id);
        List<TillageHistoryEntry> history = Tillage.withTillageYear(Tillage.parseTillageHistory(parcel.getTillageHistory()),
                new TillageHistoryEntry(year != null ? year : Times.currentLocalYear(), system));
        TillageSystem current = Tillage.tillageInYear(history, Times.currentLocalYear());
        parcel.setTillageSystem(current == null ? null : current.name());
        parcel.setTillageHistory(mapper.valueToTree(history));
        return ParcelView.of(parcels.saveAndFlush(parcel));
    }

    public ParcelView setArchived(UUID farmId, UUID id, boolean archived) {
        Parcel parcel = access.requireParcel(farmId, id);
        parcel.setArchivedAt(archived ? Times.now() : null);
        return ParcelView.of(parcels.saveAndFlush(parcel));
    }

    public Parcel require(UUID farmId, UUID id) {
        return parcels.findByIdAndFarmId(id, farmId).orElseThrow(() -> Errors.notFound("Parcel"));
    }
}
