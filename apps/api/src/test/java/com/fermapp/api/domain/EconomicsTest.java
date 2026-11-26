package com.fermapp.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.domain.economics.EconomicModels.EconomicCategory;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSettings;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSheet;
import com.fermapp.api.domain.economics.EconomicModels.ParcelEconomicsResult;
import com.fermapp.api.domain.economics.EconomicModels.SeedCost;
import com.fermapp.api.domain.economics.EconomicModels.SeedLine;
import com.fermapp.api.domain.economics.Economics;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Real production cost per hectare. */
class EconomicsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void seedCostPerHectareAndBags() {
        SeedCost cost = Economics.seedCost(new SeedLine("s1", "Pioneer", "P0710", null, "SEEDS", 80_000, 70_000, 180, null), 10);
        assertThat(cost.costPerHa()).isEqualTo(157.5);
        assertThat(cost.bagsNeeded()).isEqualTo(8.8);
        assertThat(cost.total()).isEqualTo(1575.0);
        assertThat(cost.costPer1000Seeds()).isEqualTo(2.25);
        assertThat(cost.costPerKg()).isNull();
    }

    @Test
    void parseSheetKeepsOnlyWellFormedLines() throws Exception {
        EconomicSheet sheet = Economics.parseSheet(mapper.readTree("""
                {"seeds":[{"id":"","unit":"KG","bagSize":25,"ratePerHa":220,"pricePerBag":18}, 5],
                 "chemicals":[{"kind":"UNKNOWN"}],"revenue":{"pricePerT":"x"}}"""));
        assertThat(sheet.seeds()).hasSize(1);
        assertThat(sheet.seeds().get(0).id()).isEqualTo("line-0");
        assertThat(sheet.chemicals().get(0).kind().name()).isEqualTo("HERBICIDE");
        assertThat(sheet.revenue().pricePerT()).isNull();
        assertThat(sheet.fertilizers()).isEmpty();
    }

    @Test
    void seasonCostIncludesProjectedOperations() {
        EconomicSettings settings = Economics.parseSettings(null);
        EconomicSheet sheet = Economics.parseSheet(null);
        ParcelEconomicsResult result = Economics.computeParcelEconomics(10, sheet, List.of(), settings,
                TillageSystem.NO_TILL, CropCode.CORN, List.of());
        assertThat(result.toDate().total()).isZero();
        assertThat(result.projected()).extracting(op -> op.type()).containsExactly("SOWING", "HARVESTING");
        assertThat(result.season().byCategory().get(EconomicCategory.FUEL)).isPositive();
        assertThat(result.season().total()).isEqualTo(result.season().byCategory().values().stream()
                .mapToDouble(Double::doubleValue).sum(), org.assertj.core.data.Offset.offset(0.05));
    }
}
