package com.fermapp.api.modules.soil

import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.SoilStatus
import com.fermapp.api.domain.soil.SoilRules
import com.fermapp.api.integrations.SoilGridsClient
import com.fermapp.api.persistence.entity.Parcel
import com.fermapp.api.persistence.entity.SoilProfile
import com.fermapp.api.persistence.repository.SoilProfileRepository
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.stereotype.Service

/** Queries SoilGrids and saves the profile; an error remains visible in the status (soil.service.ts). */
@Service
class SoilService(
    private val profiles: SoilProfileRepository,
    private val soilGrids: SoilGridsClient,
    // The parameter name selects the application task executor among the executor beans.
    private val applicationTaskExecutor: AsyncTaskExecutor,
) {

    private fun profileFor(parcelId: UUID): SoilProfile =
        profiles.findByParcelId(parcelId).orElseGet { SoilProfile().apply { this.parcelId = parcelId } }

    fun refresh(parcelId: UUID, lat: Double, lng: Double): SoilProfile {
        var profile = profileFor(parcelId)
        profile.status = SoilStatus.PENDING
        profile.errorMessage = null
        profile = profiles.saveAndFlush(profile)

        try {
            val values = soilGrids.fetch(lat, lng)
            if (values == null) {
                profile.status = SoilStatus.FAILED
                profile.errorMessage = "SoilGrids has no data for this location."
                return profiles.saveAndFlush(profile)
            }
            val soil = SoilRules.classifySoil(values)
            profile.status = SoilStatus.READY
            profile.ph = values.ph
            profile.clayPct = values.clayPct
            profile.sandPct = values.sandPct
            profile.siltPct = soil.siltPct
            profile.organicCarbonGKg = values.organicCarbonGKg
            profile.organicMatterPct = soil.organicMatterPct
            profile.waterHoldingMmPerM = soil.waterHoldingMmPerM
            profile.texture = soil.texture.name
            profile.soilType = soil.soilType
            profile.label = soil.label
            profile.errorMessage = null
            profile.fetchedAt = Times.now()
            return profiles.saveAndFlush(profile)
        } catch (error: RuntimeException) {
            log.warn("Soil profile unavailable for parcel {}: {}", parcelId, error.message)
            profile.status = SoilStatus.FAILED
            profile.errorMessage = "SoilGrids is not responding right now. It will be retried automatically."
            return profiles.saveAndFlush(profile)
        }
    }

    /** Starts the analysis without waiting for it, as after creating or redrawing a parcel. */
    fun loadInBackground(parcel: Parcel) {
        val id = parcel.id
        val lat = parcel.centerLat
        val lng = parcel.centerLng
        applicationTaskExecutor.execute {
            try {
                refresh(id, lat, lng)
            } catch (error: RuntimeException) {
                log.error("Soil profile failed", error)
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SoilService::class.java)
    }
}
