package com.fermapp.api.modules.intelligence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fermapp.api.domain.model.SoilStatus
import com.fermapp.api.domain.soil.SoilRules
import com.fermapp.api.modules.common.Views.SoilProfileView
import com.fermapp.api.persistence.entity.SoilProfile

/**
 * The soil part of the parcel panel (soilSection in intelligence.service.ts):
 * `{ "status": "PENDING" }` when there is no profile, otherwise every profile field plus
 * `indices` (null until the profile is complete).
 */
object SoilSection {

    @JvmStatic
    fun of(profile: SoilProfile?, mapper: ObjectMapper): ObjectNode {
        if (profile == null) {
            val pending = mapper.createObjectNode()
            pending.put("status", SoilStatus.PENDING.name)
            return pending
        }
        val ph = profile.ph
        val organicMatter = profile.organicMatterPct
        val clay = profile.clayPct
        val sand = profile.sandPct
        val waterHolding = profile.waterHoldingMmPerM
        val indices = if (profile.status == SoilStatus.READY && ph != null && organicMatter != null && clay != null &&
            sand != null && waterHolding != null
        ) {
            SoilRules.soilIndices(ph, organicMatter, clay, sand, waterHolding)
        } else {
            null
        }
        val section: ObjectNode = mapper.valueToTree(SoilProfileView.of(profile))
        section.set<JsonNode>("indices", mapper.valueToTree<JsonNode>(indices))
        return section
    }
}
