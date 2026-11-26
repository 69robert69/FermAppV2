package com.fermapp.api.modules.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fermapp.api.domain.model.SoilStatus;
import com.fermapp.api.domain.soil.SoilRules;
import com.fermapp.api.domain.soil.SoilRules.SoilIndices;
import com.fermapp.api.modules.common.Views.SoilProfileView;
import com.fermapp.api.persistence.entity.SoilProfile;

/**
 * The soil part of the parcel panel (soilSection in intelligence.service.ts):
 * {@code { "status": "PENDING" }} when there is no profile, otherwise every profile field plus
 * {@code indices} (null until the profile is complete).
 */
public final class SoilSection {

    private SoilSection() {
    }

    public static ObjectNode of(SoilProfile profile, ObjectMapper mapper) {
        if (profile == null) {
            ObjectNode pending = mapper.createObjectNode();
            pending.put("status", SoilStatus.PENDING.name());
            return pending;
        }
        boolean ready = profile.getStatus() == SoilStatus.READY && profile.getPh() != null && profile.getOrganicMatterPct() != null
                && profile.getClayPct() != null && profile.getSandPct() != null && profile.getWaterHoldingMmPerM() != null;
        SoilIndices indices = ready
                ? SoilRules.soilIndices(profile.getPh(), profile.getOrganicMatterPct(), profile.getClayPct(), profile.getSandPct(),
                        profile.getWaterHoldingMmPerM())
                : null;
        ObjectNode section = mapper.valueToTree(SoilProfileView.of(profile));
        section.set("indices", mapper.valueToTree(indices));
        return section;
    }
}
