package com.fermapp.api.domain.economics;

import java.util.List;
import java.util.Optional;

/**
 * The part of catalog.ts the server uses: indicative values for common trailed implements
 * and the default diesel price. The seed and tractor catalogs live only in the mobile app.
 */
public final class ImplementCatalog {

    /** Diesel price in euro per litre. */
    public static final double DEFAULT_FUEL_PRICE_EUR = 1.45;

    /**
     * A trailed implement.
     *
     * @param hpPerMeter power required for each metre of working width
     * @param fuelLoad share of the tractor's consumption range used for this operation
     */
    public record ImplementEntry(String id, String name, String operation, List<Double> widthsM, double speedKmh,
                                 double efficiencyPct, double hpPerMeter, double fuelLoad) {
    }

    public static final List<ImplementEntry> IMPLEMENTS = List.of(
            new ImplementEntry("plug", "Reversible plough", "PLOWING", List.of(1.6, 2.1, 2.8, 3.5), 7, 80, 90, 0.8),
            new ImplementEntry("disc", "Disc harrow", "DISCING", List.of(3.0, 4.0, 6.0, 8.0), 11, 80, 30, 0.6),
            new ImplementEntry("scarificator", "Subsoiler", "SUBSOILING", List.of(2.5, 3.0, 4.0, 5.0), 8, 80, 70, 0.8),
            new ImplementEntry("semanatoare", "Seed drill", "SOWING", List.of(3.0, 4.5, 6.0, 8.0, 12.0), 8, 70, 22, 0.35),
            new ImplementEntry("fertilizator", "Fertilizer spreader", "FERTILIZING", List.of(12.0, 18.0, 24.0), 12, 70, 3, 0.25),
            new ImplementEntry("erbicidator", "Field sprayer", "SPRAYING", List.of(18.0, 24.0, 28.0), 10, 70, 4, 0.25));

    private ImplementCatalog() {
    }

    public static Optional<ImplementEntry> forOperation(String operation) {
        return IMPLEMENTS.stream().filter(item -> item.operation().equals(operation)).findFirst();
    }
}
