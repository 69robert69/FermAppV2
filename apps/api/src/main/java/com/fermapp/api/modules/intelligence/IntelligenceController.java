package com.fermapp.api.modules.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.storage.FileStorage;
import com.fermapp.api.common.storage.FileStorage.ImageType;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.domain.report.ReportTable;
import com.fermapp.api.domain.report.ReportTable.Column;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.IncomeView;
import com.fermapp.api.modules.common.Views.ObservationView;
import com.fermapp.api.modules.common.Views.PhotoView;
import com.fermapp.api.modules.common.Views.TaskWithAssignee;
import com.fermapp.api.modules.intelligence.IntelligenceService.EconomicsView;
import com.fermapp.api.modules.intelligence.IntelligenceService.HistoryItem;
import com.fermapp.api.modules.intelligence.IntelligenceService.IntelligenceData;
import com.fermapp.api.modules.intelligence.IntelligenceService.NdviComparison;
import com.fermapp.api.modules.intelligence.IntelligenceService.ParcelWeather;
import com.fermapp.api.modules.intelligence.IntelligenceService.RotationScore;
import com.fermapp.api.modules.intelligence.IntelligenceService.TaskPanel;
import com.fermapp.api.modules.intelligence.IntelligenceService.TaskSummary;
import com.fermapp.api.modules.reports.ReportRenderer;
import com.fermapp.api.persistence.entity.Income;
import com.fermapp.api.persistence.entity.Observation;
import com.fermapp.api.persistence.entity.Photo;
import com.fermapp.api.persistence.entity.Task;
import com.fermapp.api.persistence.repository.FarmRepository;
import com.fermapp.api.persistence.repository.IncomeRepository;
import com.fermapp.api.persistence.repository.ObservationRepository;
import com.fermapp.api.persistence.repository.PhotoRepository;
import com.fermapp.api.persistence.repository.SatelliteObservationRepository;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The Parcel Intelligence panel and its parts (intelligence.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels/{id}")
@Tag(name = "Parcel Intelligence")
public class IntelligenceController {

    private static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;

    public record IncomeRequest(@NotNull @Min(2000) @Max(2100) Integer year,
                                @NotNull @Positive @DecimalMax("100000000") Double amount,
                                @Size(max = 120) String source, Instant date) {
        public IncomeRequest {
            source = Text.trim(source);
        }
    }

    public record ObservationRequest(@NotNull @Size(min = 1, max = 2000) String text,
                                     @DecimalMin("-90") @DecimalMax("90") Double lat,
                                     @DecimalMin("-180") @DecimalMax("180") Double lng) {
        public ObservationRequest {
            text = Text.trim(text);
        }
    }

    private final IntelligenceService intelligence;
    private final AccessGuard access;
    private final IncomeRepository incomes;
    private final SatelliteObservationRepository satellite;
    private final TaskRepository tasks;
    private final ObservationRepository observations;
    private final PhotoRepository photos;
    private final FarmRepository farms;
    private final FileStorage storage;
    private final RealtimeHub realtime;
    private final ReportRenderer renderer;
    private final TransactionTemplate tx;

    public IntelligenceController(IntelligenceService intelligence, AccessGuard access, IncomeRepository incomes,
                                  SatelliteObservationRepository satellite, TaskRepository tasks, ObservationRepository observations,
                                  PhotoRepository photos, FarmRepository farms, FileStorage storage, RealtimeHub realtime,
                                  ReportRenderer renderer, TransactionTemplate tx) {
        this.intelligence = intelligence;
        this.access = access;
        this.incomes = incomes;
        this.satellite = satellite;
        this.tasks = tasks;
        this.observations = observations;
        this.photos = photos;
        this.farms = farms;
        this.storage = storage;
        this.realtime = realtime;
        this.renderer = renderer;
        this.tx = tx;
    }

    private void parcelUpdated(UUID farmId, UUID parcelId) {
        realtime.emitToFarm(farmId, RealtimeEvent.PARCEL_UPDATED, Map.of("parcelId", parcelId));
    }

    @GetMapping("/intelligence")
    @Access(AccessGroup.ALL)
    @Operation(summary = "All data for the Parcel Intelligence panel")
    public ResponseEntity<ApiResponse<IntelligenceData>> panel(AuthContext auth, @PathVariable UUID id) {
        return ApiResponse.ok(intelligence.buildIntelligence(auth, id));
    }

    @GetMapping("/weather")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Today’s weather, 7- and 14-day forecast and spraying alerts")
    public ResponseEntity<ApiResponse<ParcelWeather>> weather(AuthContext auth, @PathVariable UUID id) {
        return ApiResponse.ok(intelligence.parcelWeather(access.requireParcel(auth.farmId(), id)));
    }

    @GetMapping("/rotation-score")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Rotation score and crop history")
    public ResponseEntity<ApiResponse<RotationScore>> rotationScore(AuthContext auth, @PathVariable UUID id) {
        access.requireParcel(auth.farmId(), id);
        return ApiResponse.ok(intelligence.rotationScore(id, Times.now()));
    }

    @GetMapping("/economics")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Costs, estimated revenue, profit, margin and ROI")
    public ResponseEntity<ApiResponse<EconomicsView>> economics(AuthContext auth, @PathVariable UUID id,
                                                               @RequestParam(required = false) @Min(2000) @Max(2100) Integer year) {
        return ApiResponse.ok(intelligence.economicsFor(access.requireParcel(auth.farmId(), id), year));
    }

    @PostMapping("/incomes")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Record realized revenue")
    public ResponseEntity<ApiResponse<IncomeView>> addIncome(AuthContext auth, @PathVariable UUID id, @Valid @RequestBody IncomeRequest body) {
        access.requireParcel(auth.farmId(), id);
        Income income = new Income();
        income.setParcelId(id);
        income.setYear(body.year());
        income.setAmount(BigDecimal.valueOf(body.amount()));
        income.setSource(body.source());
        income.setDate(body.date());
        Income saved = incomes.saveAndFlush(income);
        parcelUpdated(auth.farmId(), id);
        return ApiResponse.created(IncomeView.of(incomes.findById(saved.getId()).orElse(saved)));
    }

    @GetMapping("/ndvi")
    @Access(AccessGroup.ALL)
    @Operation(summary = "NDVI, NDRE and vegetation status: now, 7 and 30 days ago")
    public ResponseEntity<ApiResponse<NdviComparison>> ndvi(AuthContext auth, @PathVariable UUID id) {
        access.requireParcel(auth.farmId(), id);
        return ApiResponse.ok(IntelligenceService.ndviComparison(
                satellite.findByParcelIdOrderByDateDesc(id, PageRequest.of(0, 40)), Times.now()));
    }

    @GetMapping("/tasks")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Parcel tasks and status summary")
    public ResponseEntity<ApiResponse<TaskPanel>> tasks(AuthContext auth, @PathVariable UUID id) {
        access.requireParcel(auth.farmId(), id);
        return ApiResponse.ok(tx.execute(status -> {
            List<Task> items = tasks.findForParcel(id, PageRequest.of(0, Integer.MAX_VALUE));
            return new TaskPanel(new TaskSummary(
                    items.stream().filter(task -> task.getStatus().name().equals("TODO")).count(),
                    items.stream().filter(task -> task.getStatus().name().equals("IN_PROGRESS")).count(),
                    items.stream().filter(task -> task.getStatus().name().equals("DONE")).count()),
                    items.stream().map(TaskWithAssignee::of).toList());
        }));
    }

    @GetMapping("/observations")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Field observations")
    public ResponseEntity<ApiResponse<List<ObservationView>>> listObservations(AuthContext auth, @PathVariable UUID id) {
        access.requireParcel(auth.farmId(), id);
        return ApiResponse.ok(observations.findLatest(id, PageRequest.of(0, 100)).stream().map(ObservationView::of).toList());
    }

    @PostMapping("/observations")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Add a field observation")
    public ResponseEntity<ApiResponse<ObservationView>> addObservation(AuthContext auth, @PathVariable UUID id,
                                                                      @Valid @RequestBody ObservationRequest body) {
        access.requireParcel(auth.farmId(), id);
        Observation observation = new Observation();
        observation.setParcelId(id);
        observation.setAuthorId(auth.userId());
        observation.setText(body.text());
        observation.setLat(body.lat());
        observation.setLng(body.lng());
        Observation saved = observations.saveAndFlush(observation);
        ObservationView view = tx.execute(status -> ObservationView.of(observations.findById(saved.getId()).orElseThrow()));
        parcelUpdated(auth.farmId(), id);
        return ApiResponse.created(view);
    }

    @GetMapping("/photos")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Parcel photo gallery")
    public ResponseEntity<ApiResponse<List<PhotoView>>> listPhotos(AuthContext auth, @PathVariable UUID id) {
        access.requireParcel(auth.farmId(), id);
        return ApiResponse.ok(photos.findByParcelIdOrderByCreatedAtDesc(id, PageRequest.of(0, 200)).stream().map(PhotoView::of).toList());
    }

    @PostMapping(value = "/photos", consumes = "multipart/form-data")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Upload a photo with notes")
    public ResponseEntity<ApiResponse<PhotoView>> uploadPhoto(AuthContext auth, @PathVariable UUID id,
                                                             @RequestPart(name = "file", required = false) MultipartFile file,
                                                             @RequestParam(name = "notes", required = false) @Size(max = 1000) String notes)
            throws IOException {
        access.requireParcel(auth.farmId(), id);
        if (file == null || file.isEmpty()) {
            throw Errors.badRequest("Attach the photo in the \"file\" field.");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw Errors.badRequest("The photo exceeds the 10 MB limit.");
        }
        byte[] content = file.getBytes();
        ImageType type = FileStorage.detectImageType(content);
        if (type == null) {
            throw Errors.badRequest("The file must be a JPEG, PNG, WEBP or HEIC image.");
        }
        String storageKey = storage.save("parcels/" + id, type.extension(), content);
        Photo photo = new Photo();
        photo.setParcelId(id);
        photo.setAuthorId(auth.userId());
        photo.setStorageKey(storageKey);
        photo.setContentType(type.contentType());
        photo.setSizeBytes((int) file.getSize());
        String trimmed = Text.trim(notes);
        photo.setNotes(trimmed == null || trimmed.isEmpty() ? null : trimmed);
        Photo saved = photos.saveAndFlush(photo);
        parcelUpdated(auth.farmId(), id);
        return ApiResponse.created(PhotoView.of(saved));
    }

    private Photo requirePhoto(UUID farmId, UUID parcelId, UUID photoId) {
        return photos.findInFarm(farmId, parcelId, photoId).orElseThrow(() -> Errors.notFound("Photo"));
    }

    @GetMapping("/photos/{photoId}/file")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Photo content")
    public ResponseEntity<byte[]> photoFile(AuthContext auth, @PathVariable UUID id, @PathVariable UUID photoId) {
        Photo photo = requirePhoto(auth.farmId(), id, photoId);
        byte[] content;
        try {
            content = storage.load(photo.getStorageKey());
        } catch (IOException | IllegalArgumentException error) {
            throw Errors.notFound("Photo file");
        }
        return ApiResponse.file(content, photo.getContentType(), "photo-" + photo.getId(), true);
    }

    @DeleteMapping("/photos/{photoId}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Delete a photo")
    public ResponseEntity<Void> deletePhoto(AuthContext auth, @PathVariable UUID id, @PathVariable UUID photoId) {
        Photo photo = requirePhoto(auth.farmId(), id, photoId);
        boolean mayDelete = auth.role() == Role.ADMIN || auth.role() == Role.FARM_MANAGER || photo.getAuthorId().equals(auth.userId());
        if (!mayDelete) {
            throw Errors.forbidden("You can only delete your own photos.");
        }
        photos.delete(photo);
        storage.remove(photo.getStorageKey());
        parcelUpdated(auth.farmId(), id);
        return ApiResponse.noContent();
    }

    private static Map<String, Object> row(String section, String indicator, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("section", section);
        map.put("indicator", indicator);
        map.put("value", value == null ? "Data unavailable" : value);
        return map;
    }

    @GetMapping("/report")
    @Access(AccessGroup.ALL)
    @Operation(summary = "PDF report for the parcel")
    public ResponseEntity<byte[]> report(AuthContext auth, @PathVariable UUID id) {
        IntelligenceData data = intelligence.buildIntelligence(auth, id);
        String farmName = farms.findById(auth.farmId()).orElseThrow(Errors::recordNotFound).getName();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Parcel", "Code", data.header().code()));
        rows.add(row("Parcel", "Area (ha)", data.header().areaHa()));
        String locality = String.join(", ", java.util.stream.Stream.of(data.header().locality(), data.header().county())
                .filter(value -> value != null && !value.isEmpty()).toList());
        rows.add(row("Parcel", "Locality", locality));
        rows.add(row("Crop", "Current crop", data.crop() != null ? data.crop().name() : "Not recorded"));
        rows.add(row("Crop", "Progress (%)", data.crop() != null ? data.crop().progress().percent() : null));
        rows.add(row("Rotation", "Score", data.rotation().result().score()));
        rows.add(row("Rotation", "Verdict", data.rotation().result().label().replaceFirst("^\\S+\\s", "")));
        rows.add(row("Rotation", "Explanation", data.rotation().result().explanation()));
        for (HistoryItem item : data.rotation().history()) {
            rows.add(row("History", String.valueOf(item.year()), Crops.name(item.crop())));
        }
        rows.add(row("Recommendation", "Next action", data.nextAction().action()));
        rows.add(row("Recommendation", "Reason", data.nextAction().reason()));
        JsonNode label = data.soil().get("label");
        rows.add(row("Soil", "Classification", label == null || label.isNull() ? null : label.asText()));
        rows.add(row("Tasks", "Open", data.tasks().summary().todo() + data.tasks().summary().inProgress()));
        if (data.economics() != null) {
            rows.add(row("Economics", "Total cost (EUR)", data.economics().economics().totalCost()));
            rows.add(row("Economics", "Cost/ha (EUR)", data.economics().economics().costPerHa()));
            rows.add(row("Economics", "Estimated profit (EUR)", data.economics().economics().expectedProfit()));
        }
        ReportTable report = new ReportTable("Parcel " + data.header().name(), "Generated on " + data.generatedAt().substring(0, 10),
                List.of(Column.text("section", "Section", 1), Column.text("indicator", "Indicator", 1.4), Column.text("value", "Value", 3.6)),
                rows, null, null, null, null);
        return ApiResponse.file(renderer.toPdf(report, farmName, null), "application/pdf", "parcel-" + data.header().code() + ".pdf", false);
    }
}
