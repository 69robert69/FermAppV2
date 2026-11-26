package com.fermapp.api.modules.parcels;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.modules.common.Views.ParcelView;
import com.fermapp.api.modules.parcels.GeoIo.ExportFile;
import com.fermapp.api.modules.parcels.ParcelDtos.ImportResponse;
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDetail;
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDraft;
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelListItem;
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelUpdate;
import com.fermapp.api.modules.parcels.ParcelDtos.TillageRequest;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Parcel endpoints (parcels.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels")
@Tag(name = "Parcels")
public class ParcelController {

    private static final String FORMATS = "geojson|kml|kmz|shp|lpis";

    private final ParcelService parcels;

    public ParcelController(ParcelService parcels) {
        this.parcels = parcels;
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List the farm’s parcels")
    public ResponseEntity<ApiResponse<List<ParcelListItem>>> list(
            AuthContext auth,
            @RequestParam(defaultValue = "false") @Pattern(regexp = "true|false") String archived,
            @RequestParam(required = false) @Size(max = 120) String search) {
        String trimmed = search == null ? null : search.trim();
        return ApiResponse.ok(parcels.list(auth.farmId(), "true".equals(archived), trimmed));
    }

    @PostMapping
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Create a parcel and start the soil analysis")
    public ResponseEntity<ApiResponse<ParcelView>> create(AuthContext auth, @Valid @RequestBody ParcelDraft body) {
        return ApiResponse.created(parcels.create(auth.farmId(), body));
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Import parcels from GeoJSON, KML, KMZ, SHP or APIA LPIS")
    public ResponseEntity<ApiResponse<ImportResponse>> importParcels(
            AuthContext auth,
            @RequestParam @Pattern(regexp = FORMATS) String format,
            @RequestParam(defaultValue = "false") @Pattern(regexp = "true|false") String dryRun,
            @RequestPart(name = "file", required = false) MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw Errors.badRequest("Attach the file in the \"file\" field.");
        }
        return ApiResponse.created(parcels.importFile(auth.farmId(), format, "true".equals(dryRun), file.getBytes()));
    }

    @GetMapping("/export")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Export active parcels")
    public ResponseEntity<byte[]> export(AuthContext auth, @RequestParam @Pattern(regexp = FORMATS) String format) {
        ExportFile file = parcels.export(auth.farmId(), format);
        return ApiResponse.file(file.content(), file.contentType(), "parcels." + file.extension(), false);
    }

    @GetMapping("/{id}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Parcel details")
    public ResponseEntity<ApiResponse<ParcelDetail>> detail(AuthContext auth, @PathVariable UUID id) {
        return ApiResponse.ok(parcels.detail(auth.farmId(), id));
    }

    @PatchMapping("/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Update the parcel data or outline")
    public ResponseEntity<ApiResponse<ParcelView>> update(AuthContext auth, @PathVariable UUID id, @Valid @RequestBody ParcelUpdate body) {
        return ApiResponse.ok(parcels.update(auth.farmId(), id, body));
    }

    @PostMapping("/{id}/tillage")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Set the tillage technology of a year; the current one follows the history")
    public ResponseEntity<ApiResponse<ParcelView>> tillage(AuthContext auth, @PathVariable UUID id, @Valid @RequestBody TillageRequest body) {
        return ApiResponse.ok(parcels.setTillage(auth.farmId(), id, body.system(), body.year()));
    }

    @PostMapping("/{id}/archive")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Archive the parcel")
    public ResponseEntity<ApiResponse<ParcelView>> archive(AuthContext auth, @PathVariable UUID id) {
        return ApiResponse.ok(parcels.setArchived(auth.farmId(), id, true));
    }

    @PostMapping("/{id}/restore")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Restore the parcel from the archive")
    public ResponseEntity<ApiResponse<ParcelView>> restore(AuthContext auth, @PathVariable UUID id) {
        return ApiResponse.ok(parcels.setArchived(auth.farmId(), id, false));
    }
}
