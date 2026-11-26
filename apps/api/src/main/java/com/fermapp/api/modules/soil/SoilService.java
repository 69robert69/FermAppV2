package com.fermapp.api.modules.soil;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.SoilStatus;
import com.fermapp.api.domain.soil.SoilRules;
import com.fermapp.api.domain.soil.SoilRules.SoilClassification;
import com.fermapp.api.domain.soil.SoilRules.SoilInput;
import com.fermapp.api.integrations.SoilGridsClient;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.SoilProfile;
import com.fermapp.api.persistence.repository.SoilProfileRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/** Queries SoilGrids and saves the profile; an error remains visible in the status (soil.service.ts). */
@Service
public class SoilService {

    private static final Logger log = LoggerFactory.getLogger(SoilService.class);

    private final SoilProfileRepository profiles;
    private final SoilGridsClient soilGrids;
    private final AsyncTaskExecutor executor;

    public SoilService(SoilProfileRepository profiles, SoilGridsClient soilGrids, AsyncTaskExecutor applicationTaskExecutor) {
        this.profiles = profiles;
        this.soilGrids = soilGrids;
        this.executor = applicationTaskExecutor;
    }

    private SoilProfile profileFor(UUID parcelId) {
        return profiles.findByParcelId(parcelId).orElseGet(() -> {
            SoilProfile created = new SoilProfile();
            created.setParcelId(parcelId);
            return created;
        });
    }

    public SoilProfile refresh(UUID parcelId, double lat, double lng) {
        SoilProfile profile = profileFor(parcelId);
        profile.setStatus(SoilStatus.PENDING);
        profile.setErrorMessage(null);
        profile = profiles.saveAndFlush(profile);

        try {
            SoilInput values = soilGrids.fetch(lat, lng);
            if (values == null) {
                profile.setStatus(SoilStatus.FAILED);
                profile.setErrorMessage("SoilGrids has no data for this location.");
                return profiles.saveAndFlush(profile);
            }
            SoilClassification soil = SoilRules.classifySoil(values);
            profile.setStatus(SoilStatus.READY);
            profile.setPh(values.ph());
            profile.setClayPct(values.clayPct());
            profile.setSandPct(values.sandPct());
            profile.setSiltPct(soil.siltPct());
            profile.setOrganicCarbonGKg(values.organicCarbonGKg());
            profile.setOrganicMatterPct(soil.organicMatterPct());
            profile.setWaterHoldingMmPerM(soil.waterHoldingMmPerM());
            profile.setTexture(soil.texture().name());
            profile.setSoilType(soil.soilType());
            profile.setLabel(soil.label());
            profile.setErrorMessage(null);
            profile.setFetchedAt(Times.now());
            return profiles.saveAndFlush(profile);
        } catch (RuntimeException error) {
            log.warn("Soil profile unavailable for parcel {}: {}", parcelId, error.getMessage());
            profile.setStatus(SoilStatus.FAILED);
            profile.setErrorMessage("SoilGrids is not responding right now. It will be retried automatically.");
            return profiles.saveAndFlush(profile);
        }
    }

    /** Starts the analysis without waiting for it, as after creating or redrawing a parcel. */
    public void loadInBackground(Parcel parcel) {
        UUID id = parcel.getId();
        double lat = parcel.getCenterLat();
        double lng = parcel.getCenterLng();
        executor.execute(() -> {
            try {
                refresh(id, lat, lng);
            } catch (RuntimeException error) {
                log.error("Soil profile failed", error);
            }
        });
    }
}
