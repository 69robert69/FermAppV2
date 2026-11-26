package com.fermapp.api.modules.soil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.SoilProfileView;
import com.fermapp.api.modules.intelligence.SoilSection;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.repository.SoilProfileRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The parcel soil profile (soil.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels/{id}/soil")
@Tag(name = "Soil")
public class SoilController {

    private final AccessGuard access;
    private final SoilProfileRepository profiles;
    private final SoilService soil;
    private final ObjectMapper mapper;

    public SoilController(AccessGuard access, SoilProfileRepository profiles, SoilService soil, ObjectMapper mapper) {
        this.access = access;
        this.profiles = profiles;
        this.soil = soil;
        this.mapper = mapper;
    }

    /** {@code { parcelId, ...soilSection(profile) }}. */
    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "Soil profile of the parcel")
    public ResponseEntity<ApiResponse<ObjectNode>> get(AuthContext auth, @PathVariable UUID id) {
        access.requireParcel(auth.farmId(), id);
        ObjectNode response = mapper.createObjectNode();
        response.put("parcelId", id.toString());
        response.setAll(SoilSection.of(profiles.findByParcelId(id).orElse(null), mapper));
        return ApiResponse.ok(response);
    }

    @PostMapping("/refresh")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Query SoilGrids again")
    public ResponseEntity<ApiResponse<SoilProfileView>> refresh(AuthContext auth, @PathVariable UUID id) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        return ApiResponse.ok(SoilProfileView.of(soil.refresh(parcel.getId(), parcel.getCenterLat(), parcel.getCenterLng())));
    }
}
