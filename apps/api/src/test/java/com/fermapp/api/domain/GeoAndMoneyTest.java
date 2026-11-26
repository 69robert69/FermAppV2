package com.fermapp.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fermapp.api.domain.economics.Money;
import com.fermapp.api.domain.geo.Geo;
import com.fermapp.api.domain.geo.Geo.GeoMetrics;
import com.fermapp.api.domain.geo.Geo.Polygon;
import com.fermapp.api.domain.geo.Geo.Position;
import com.fermapp.api.domain.work.WorkMath;
import com.fermapp.api.domain.work.WorkMath.TimedProgress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parcel geometry, euro formatting and timed progress. */
class GeoAndMoneyTest {

    private static Polygon square(double size) {
        return Polygon.of(List.of(List.of(new Position(26, 44.7), new Position(26 + size, 44.7),
                new Position(26 + size, 44.7 + size), new Position(26, 44.7 + size))));
    }

    @Test
    void computesAreaPerimeterAndCentre() {
        GeoMetrics metrics = Geo.computeGeoMetrics(square(0.01));
        assertThat(metrics.areaHa()).isEqualTo(87.8781);
        assertThat(metrics.center().lat()).isEqualTo(44.705);
        assertThat(metrics.center().lng()).isEqualTo(26.005);
        assertThat(metrics.perimeterM()).isBetween(3800.0, 3900.0);
    }

    @Test
    void rejectsSelfIntersectingOutlines() {
        Polygon bowTie = Polygon.of(List.of(List.of(new Position(26, 44.7), new Position(26.01, 44.71),
                new Position(26.01, 44.7), new Position(26, 44.71))));
        assertThat(Geo.validatePolygon(bowTie)).isEqualTo("The parcel outline intersects itself.");
        assertThat(Geo.validatePolygon(square(0.01))).isNull();
    }

    @Test
    void formatsEuroLikeTheApp() {
        assertThat(Money.formatEuro(1250.0)).isEqualTo("€1.250");
        assertThat(Money.formatEuro(12450.5)).isEqualTo("€12.450,50");
        assertThat(Money.formatEuro(-3.456, 2)).isEqualTo("-€3,46");
        assertThat(Money.formatEuro(null)).isEqualTo("-");
        assertThat(Money.toEuro(497, "RON")).isEqualTo(100.0);
    }

    @Test
    void pausesStopTheTimer() {
        Instant start = Instant.parse("2026-05-01T08:00:00Z");
        TimedProgress progress = WorkMath.timedProgress(start, 4 * 3_600_000, 3_600_000, null, start.plusSeconds(3 * 3600));
        assertThat(progress.percent()).isEqualTo(50.0);
        assertThat(progress.finished()).isFalse();
        assertThat(progress.eta()).isEqualTo(start.plusSeconds(5 * 3600));
    }
}
