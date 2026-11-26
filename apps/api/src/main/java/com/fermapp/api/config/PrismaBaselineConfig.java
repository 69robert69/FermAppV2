package com.fermapp.api.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Takes over a database created by the TypeScript server. The Flyway scripts V1..V5 are the
 * Prisma migrations, in order; when the database has a {@code _prisma_migrations} table and no
 * Flyway history yet, it is baselined at the last Prisma migration that was applied, so the
 * remaining ones run normally.
 */
@Configuration
public class PrismaBaselineConfig {

    private static final Logger log = LoggerFactory.getLogger(PrismaBaselineConfig.class);

    /** The Prisma migration folders, in the order of the Flyway versions 1..n. */
    static final List<String> PRISMA_MIGRATIONS = List.of(
            "20261118000000_init",
            "20261119000000_parcel_intelligence",
            "20261120000000_map_and_parcel_plan",
            "20261121000000_tillage_systems",
            "20261122000000_euro_economics");

    @Bean
    FlywayMigrationStrategy prismaAwareMigration(DataSource dataSource) {
        return flyway -> {
            int applied = appliedPrismaMigrations(dataSource);
            if (applied > 0) {
                log.info("Prisma database found: baselining at version {} of {}", applied, PRISMA_MIGRATIONS.size());
                Flyway.configure()
                        .configuration(flyway.getConfiguration())
                        .baselineOnMigrate(true)
                        .baselineVersion(String.valueOf(applied))
                        .baselineDescription("Prisma migrations")
                        .load()
                        .migrate();
                return;
            }
            flyway.migrate();
        };
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(null, null, table, new String[] {"TABLE"})) {
            return tables.next();
        }
    }

    /**
     * The number of leading Prisma migrations that finished, or 0 when this is not a Prisma
     * database or Flyway already manages it.
     */
    static int appliedPrismaMigrations(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            if (tableExists(connection, "flyway_schema_history") || !tableExists(connection, "_prisma_migrations")) {
                return 0;
            }
            Set<String> finished = new HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT migration_name FROM \"_prisma_migrations\" WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL")) {
                while (rows.next()) {
                    finished.add(rows.getString(1));
                }
            }
            int applied = 0;
            for (String name : PRISMA_MIGRATIONS) {
                if (!finished.contains(name)) {
                    break;
                }
                applied++;
            }
            return applied;
        } catch (SQLException error) {
            log.warn("Could not read the Prisma migration history: {}", error.getMessage());
            return 0;
        }
    }
}
