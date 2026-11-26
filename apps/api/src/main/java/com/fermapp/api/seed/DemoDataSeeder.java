package com.fermapp.api.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.domain.geo.Geo;
import com.fermapp.api.domain.geo.Geo.GeoMetrics;
import com.fermapp.api.domain.geo.Geo.Polygon;
import com.fermapp.api.domain.geo.Geo.Position;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.MachineType;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.domain.model.TaskPriority;
import com.fermapp.api.domain.model.TaskType;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Farm;
import com.fermapp.api.persistence.entity.FarmMembership;
import com.fermapp.api.persistence.entity.Machine;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.Task;
import com.fermapp.api.persistence.entity.UserAccount;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.FarmMembershipRepository;
import com.fermapp.api.persistence.repository.FarmRepository;
import com.fermapp.api.persistence.repository.MachineRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.persistence.repository.UserAccountRepository;
import com.fermapp.api.security.PasswordHasher;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo data for local development (prisma/seed.ts). It runs when SEED_DEMO_DATA=true, never in
 * production, and only once: an existing demo account means the data is already there.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String DEMO_EMAIL = "demo@fermapp.local";
    private static final String DEMO_PASSWORD = "FermApp-demo-2026";

    private record DemoParcel(String name, String code, Polygon geometry, Map<Integer, CropCode> history) {
    }

    private final AppProperties properties;
    private final UserAccountRepository users;
    private final FarmRepository farms;
    private final FarmMembershipRepository memberships;
    private final ParcelRepository parcels;
    private final CropSeasonRepository seasons;
    private final MachineRepository machines;
    private final TaskRepository tasks;
    private final PasswordHasher passwords;
    private final ObjectMapper mapper;

    public DemoDataSeeder(AppProperties properties, UserAccountRepository users, FarmRepository farms,
                          FarmMembershipRepository memberships, ParcelRepository parcels, CropSeasonRepository seasons,
                          MachineRepository machines, TaskRepository tasks, PasswordHasher passwords, ObjectMapper mapper) {
        this.properties = properties;
        this.users = users;
        this.farms = farms;
        this.memberships = memberships;
        this.parcels = parcels;
        this.seasons = seasons;
        this.machines = machines;
        this.tasks = tasks;
        this.passwords = passwords;
        this.mapper = mapper;
    }

    private static Polygon rectangle(double lng, double lat, double width, double height) {
        return Polygon.of(List.of(List.of(new Position(lng, lat), new Position(lng + width, lat),
                new Position(lng + width, lat + height), new Position(lng + width * 0.15, lat + height * 1.1), new Position(lng, lat))));
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.seedDemoData()) {
            return;
        }
        if (properties.isProduction()) {
            throw new IllegalStateException("Demo data is not loaded in production.");
        }
        if (users.existsByEmail(DEMO_EMAIL)) {
            log.info("Demo data already exists.");
            return;
        }

        Farm farm = new Farm();
        farm.setName("Demo Farm");
        farm.setCounty("Ilfov");
        farms.save(farm);

        UserAccount admin = new UserAccount();
        admin.setEmail(DEMO_EMAIL);
        admin.setFullName("Demo Administrator");
        admin.setPasswordHash(passwords.hash(DEMO_PASSWORD));
        users.save(admin);

        FarmMembership membership = new FarmMembership();
        membership.setFarmId(farm.getId());
        membership.setUserId(admin.getId());
        membership.setRole(Role.ADMIN);
        memberships.save(membership);

        List<DemoParcel> demo = List.of(
                new DemoParcel("North 3", "NV-003", rectangle(26.1, 44.8, 0.012, 0.008),
                        Map.of(2026, CropCode.WHEAT, 2025, CropCode.CORN, 2024, CropCode.SUNFLOWER, 2023, CropCode.WHEAT, 2022, CropCode.SOY)),
                new DemoParcel("South-1", "SD-001", rectangle(26.12, 44.78, 0.01, 0.006),
                        Map.of(2026, CropCode.CORN, 2025, CropCode.SOY, 2024, CropCode.WHEAT, 2023, CropCode.RAPESEED)),
                new DemoParcel("Lunca Mare", "LM-010", rectangle(26.08, 44.77, 0.016, 0.01),
                        Map.of(2025, CropCode.ALFALFA, 2024, CropCode.ALFALFA, 2023, CropCode.ALFALFA)));
        for (DemoParcel item : demo) {
            GeoMetrics metrics = Geo.computeGeoMetrics(item.geometry());
            Parcel parcel = new Parcel();
            parcel.setFarmId(farm.getId());
            parcel.setName(item.name());
            parcel.setCode(item.code());
            parcel.setCounty("Ilfov");
            parcel.setGeometry(mapper.valueToTree(item.geometry()));
            parcel.setAreaHa(metrics.areaHa());
            parcel.setPerimeterM(metrics.perimeterM());
            parcel.setCenterLat(metrics.center().lat());
            parcel.setCenterLng(metrics.center().lng());
            parcels.save(parcel);
            item.history().forEach((year, crop) -> {
                CropSeason season = new CropSeason();
                season.setParcelId(parcel.getId());
                season.setYear(year);
                season.setCrop(crop);
                seasons.save(season);
            });
        }

        machines.saveAll(List.of(
                machine(farm, "Fendt 724", MachineType.TRACTOR, "Fendt", "724 Vario", 240, 22, admin),
                machine(farm, "Claas Lexion", MachineType.COMBINE, "Claas", "Lexion 760", 490, 45, null),
                machine(farm, "John Deere R4040", MachineType.SPRAYER, "John Deere", "R4040i", 310, 18, null)));

        Task task = new Task();
        task.setFarmId(farm.getId());
        task.setType(TaskType.PEST_CHECK);
        task.setTitle("Pest check");
        task.setPriority(TaskPriority.HIGH);
        task.setDueDate(Times.now().plusMillis(2 * Times.DAY_MS));
        task.setAssigneeId(admin.getId());
        tasks.save(task);

        log.info("Demo account: {} / {}", DEMO_EMAIL, DEMO_PASSWORD);
    }

    private static Machine machine(Farm farm, String name, MachineType type, String brand, String model, double power,
                                   double consumption, UserAccount operator) {
        Machine machine = new Machine();
        machine.setFarmId(farm.getId());
        machine.setName(name);
        machine.setType(type);
        machine.setBrand(brand);
        machine.setModel(model);
        machine.setPowerHp(power);
        machine.setAvgConsumptionLh(consumption);
        machine.setOperatorId(operator == null ? null : operator.getId());
        return machine;
    }
}
