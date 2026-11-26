package com.fermapp.api.modules.machines;

import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.model.MachineType;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.MachineWithOperator;
import com.fermapp.api.persistence.entity.Machine;
import com.fermapp.api.persistence.repository.MachineRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The machine fleet (machines.routes.ts). */
@RestController
@RequestMapping("/api/v1/machines")
@Tag(name = "Machines")
public class MachineController {

    public record MachineCreate(@NotNull @Size(min = 1, max = 120) String name, @NotNull MachineType type,
                                @Size(max = 60) String brand, @Size(max = 60) String model,
                                @Positive @DecimalMax("2000") Double powerHp,
                                @Positive @DecimalMax("500") Double avgConsumptionLh,
                                UUID operatorId,
                                @Size(max = 4) List<@NotNull TillageSystem> tillageSystems) {
        public MachineCreate {
            name = Text.trim(name);
            brand = Text.trim(brand);
            model = Text.trim(model);
        }
    }

    public record MachineUpdate(JsonNullable<@NotNull @Size(min = 1, max = 120) String> name,
                                JsonNullable<@NotNull MachineType> type,
                                JsonNullable<@Size(max = 60) String> brand,
                                JsonNullable<@Size(max = 60) String> model,
                                JsonNullable<@Positive @DecimalMax("2000") Double> powerHp,
                                JsonNullable<@Positive @DecimalMax("500") Double> avgConsumptionLh,
                                JsonNullable<UUID> operatorId,
                                JsonNullable<@NotNull @Size(max = 4) List<@NotNull TillageSystem>> tillageSystems) {
        public MachineUpdate {
            name = trimmed(name);
            type = type == null ? JsonNullable.undefined() : type;
            brand = trimmed(brand);
            model = trimmed(model);
            powerHp = powerHp == null ? JsonNullable.undefined() : powerHp;
            avgConsumptionLh = avgConsumptionLh == null ? JsonNullable.undefined() : avgConsumptionLh;
            operatorId = operatorId == null ? JsonNullable.undefined() : operatorId;
            tillageSystems = tillageSystems == null ? JsonNullable.undefined() : tillageSystems;
        }

        private static JsonNullable<String> trimmed(JsonNullable<String> value) {
            return value == null || !value.isPresent() ? JsonNullable.undefined() : JsonNullable.of(Text.trim(value.get()));
        }
    }

    private final MachineRepository machines;
    private final AccessGuard access;
    private final EntityManager entityManager;

    public MachineController(MachineRepository machines, AccessGuard access, EntityManager entityManager) {
        this.machines = machines;
        this.access = access;
        this.entityManager = entityManager;
    }

    private static String[] systems(List<TillageSystem> list) {
        return list == null ? new String[0] : list.stream().map(Enum::name).toArray(String[]::new);
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List machines")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<MachineWithOperator>>> list(AuthContext auth) {
        return ApiResponse.ok(machines.findActiveWithOperator(auth.farmId()).stream().map(MachineWithOperator::of).toList());
    }

    @PostMapping
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Add a machine")
    @Transactional
    public ResponseEntity<ApiResponse<MachineWithOperator>> create(AuthContext auth, @Valid @RequestBody MachineCreate body) {
        if (body.operatorId() != null) {
            access.requireMember(auth.farmId(), body.operatorId());
        }
        Machine machine = new Machine();
        machine.setFarmId(auth.farmId());
        machine.setName(body.name());
        machine.setType(body.type());
        machine.setBrand(body.brand());
        machine.setModel(body.model());
        machine.setPowerHp(body.powerHp());
        machine.setAvgConsumptionLh(body.avgConsumptionLh());
        machine.setOperatorId(body.operatorId());
        machine.setTillageSystems(systems(body.tillageSystems()));
        machines.saveAndFlush(machine);
        return ApiResponse.created(reload(machine.getId()));
    }

    /** Re-reads the row so the operator association matches the saved operatorId. */
    private MachineWithOperator reload(UUID id) {
        Machine machine = machines.findById(id).orElseThrow();
        entityManager.refresh(machine);
        return MachineWithOperator.of(machine);
    }

    @PatchMapping("/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Update a machine")
    @Transactional
    public ResponseEntity<ApiResponse<MachineWithOperator>> update(AuthContext auth, @PathVariable UUID id,
                                                                  @Valid @RequestBody MachineUpdate body) {
        Machine machine = access.requireMachine(auth.farmId(), id);
        if (body.operatorId().isPresent() && body.operatorId().get() != null) {
            access.requireMember(auth.farmId(), body.operatorId().get());
        }
        body.name().ifPresent(machine::setName);
        body.type().ifPresent(machine::setType);
        body.brand().ifPresent(machine::setBrand);
        body.model().ifPresent(machine::setModel);
        body.powerHp().ifPresent(machine::setPowerHp);
        body.avgConsumptionLh().ifPresent(machine::setAvgConsumptionLh);
        body.operatorId().ifPresent(machine::setOperatorId);
        body.tillageSystems().ifPresent(list -> machine.setTillageSystems(systems(list)));
        machines.saveAndFlush(machine);
        return ApiResponse.ok(reload(machine.getId()));
    }

    @DeleteMapping("/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Archive a machine")
    @Transactional
    public ResponseEntity<Void> archive(AuthContext auth, @PathVariable UUID id) {
        Machine machine = access.requireMachine(auth.farmId(), id);
        machine.setArchivedAt(Times.now());
        machines.save(machine);
        return ApiResponse.noContent();
    }
}
