package com.fermapp.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.rotation.Rotation;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.domain.rotation.Rotation.RotationResult;
import com.fermapp.api.domain.rotation.Rotation.RotationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The crop rotation rules, with the same expectations as the TypeScript domain tests. */
class RotationTest {

    @Test
    void cornAfterSoybeanIsRecommended() {
        RotationResult result = Rotation.evaluateRotation(CropCode.CORN, 2026,
                List.of(new CropHistoryEntry(CropCode.SOY, 2025), new CropHistoryEntry(CropCode.WHEAT, 2024)));
        assertThat(result.score()).isEqualTo(90);
        assertThat(result.verdict()).isEqualTo(RotationVerdict.RECOMMENDED);
        assertThat(result.explanation()).isEqualTo("Corn uses the nitrogen left in the soil by soybean.");
    }

    @Test
    void sunflowerTooSoonIsCappedBelowAcceptable() {
        RotationResult result = Rotation.evaluateRotation(CropCode.SUNFLOWER, 2026,
                List.of(new CropHistoryEntry(CropCode.SUNFLOWER, 2024)));
        assertThat(result.verdict()).isEqualTo(RotationVerdict.NOT_RECOMMENDED);
        assertThat(result.score()).isLessThan(Rotation.THRESHOLD_ACCEPTABLE);
        assertThat(result.factors().get(0).code()).isEqualTo("MIN_BREAK");
        assertThat(result.label()).isEqualTo("❌ Not recommended");
    }

    @Test
    void emptyHistoryIsIndicative() {
        RotationResult result = Rotation.evaluateRotation(CropCode.WHEAT, 2026, List.of());
        assertThat(result.score()).isEqualTo(70);
        assertThat(result.factors()).extracting(Rotation.RotationFactor::code).containsExactly("NO_HISTORY");
    }

    @Test
    void rankingIsSortedByScore() {
        List<RotationResult> ranked = Rotation.rankRotationOptions(2027, List.of(new CropHistoryEntry(CropCode.CORN, 2026)), Crops.CROP_CODES);
        assertThat(ranked).hasSize(Crops.CROP_CODES.size());
        assertThat(ranked).isSortedAccordingTo((a, b) -> Integer.compare(b.score(), a.score()));
    }
}
