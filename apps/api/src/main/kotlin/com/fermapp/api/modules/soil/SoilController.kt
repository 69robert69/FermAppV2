package com.fermapp.api.modules.soil

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.SoilProfileView
import com.fermapp.api.modules.intelligence.SoilSection
import com.fermapp.api.persistence.repository.SoilProfileRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The parcel soil profile (soil.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels/{id}/soil")
@Tag(name = "Soil")
class SoilController(
    private val access: AccessGuard,
    private val profiles: SoilProfileRepository,
    private val soil: SoilService,
    private val mapper: ObjectMapper,
) {

    /** `{ parcelId, ...soilSection(profile) }`. */
    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "Soil profile of the parcel")
    fun get(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<ObjectNode>> {
        access.requireParcel(auth.farmId(), id)
        val response = mapper.createObjectNode()
        response.put("parcelId", id.toString())
        response.setAll<ObjectNode>(SoilSection.of(profiles.findByParcelId(id).orElse(null), mapper))
        return ApiResponse.ok(response)
    }

    @PostMapping("/refresh")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Query SoilGrids again")
    fun refresh(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<SoilProfileView?>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        return ApiResponse.ok(SoilProfileView.of(soil.refresh(parcel.id, parcel.centerLat, parcel.centerLng)))
    }
}
