package com.fermapp.api.modules.machines

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.model.MachineType
import com.fermapp.api.domain.tillage.Tillage.TillageSystem
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.MachineWithOperator
import com.fermapp.api.persistence.entity.Machine
import com.fermapp.api.persistence.repository.MachineRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.EntityManager
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.util.UUID
import org.openapitools.jackson.nullable.JsonNullable
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The machine fleet (machines.routes.ts). */
@RestController
@RequestMapping("/api/v1/machines")
@Tag(name = "Machines")
class MachineController(
    private val machines: MachineRepository,
    private val access: AccessGuard,
    private val entityManager: EntityManager,
) {

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("name", "type", "brand", "model", "powerHp", "avgConsumptionLh", "operatorId", "tillageSystems")
    class MachineCreate(
        name: String?,
        @field:NotNull val type: MachineType?,
        brand: String?,
        model: String?,
        @field:Positive @field:DecimalMax("2000") val powerHp: Double?,
        @field:Positive @field:DecimalMax("500") val avgConsumptionLh: Double?,
        val operatorId: UUID?,
        @field:Size(max = 4) val tillageSystems: List<@NotNull TillageSystem>?,
    ) {
        @field:NotNull @field:Size(min = 1, max = 120)
        val name: String? = Text.trim(name)

        @field:Size(max = 60)
        val brand: String? = Text.trim(brand)

        @field:Size(max = 60)
        val model: String? = Text.trim(model)
    }

    /** A partial update: absent fields stay as they are, null clears the nullable ones. */
    class MachineUpdate(
        name: JsonNullable<String>?,
        type: JsonNullable<MachineType>?,
        brand: JsonNullable<String>?,
        model: JsonNullable<String>?,
        powerHp: JsonNullable<Double>?,
        avgConsumptionLh: JsonNullable<Double>?,
        operatorId: JsonNullable<UUID>?,
        tillageSystems: JsonNullable<List<TillageSystem>>?,
    ) {
        val name: JsonNullable<@NotNull @Size(min = 1, max = 120) String> = trimmed(name)
        val type: JsonNullable<@NotNull MachineType> = type ?: JsonNullable.undefined()
        val brand: JsonNullable<@Size(max = 60) String> = trimmed(brand)
        val model: JsonNullable<@Size(max = 60) String> = trimmed(model)
        val powerHp: JsonNullable<@Positive @DecimalMax("2000") Double> = powerHp ?: JsonNullable.undefined()
        val avgConsumptionLh: JsonNullable<@Positive @DecimalMax("500") Double> = avgConsumptionLh ?: JsonNullable.undefined()
        val operatorId: JsonNullable<UUID> = operatorId ?: JsonNullable.undefined()
        val tillageSystems: JsonNullable<@NotNull @Size(max = 4) List<@NotNull TillageSystem>> =
            tillageSystems ?: JsonNullable.undefined()

        private companion object {
            fun trimmed(value: JsonNullable<String>?): JsonNullable<String> =
                if (value == null || !value.isPresent) JsonNullable.undefined() else JsonNullable.of(Text.trim(value.get()))
        }
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List machines")
    @Transactional(readOnly = true)
    fun list(auth: AuthContext): ResponseEntity<ApiResponse<List<MachineWithOperator>>> =
        ApiResponse.ok(machines.findActiveWithOperator(auth.farmId()).map { MachineWithOperator.of(it) })

    @PostMapping
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Add a machine")
    @Transactional
    fun create(auth: AuthContext, @Valid @RequestBody body: MachineCreate): ResponseEntity<ApiResponse<MachineWithOperator>> {
        if (body.operatorId != null) {
            access.requireMember(auth.farmId(), body.operatorId)
        }
        val machine = Machine()
        machine.farmId = auth.farmId()
        machine.name = body.name!!
        machine.type = body.type!!
        machine.brand = body.brand
        machine.model = body.model
        machine.powerHp = body.powerHp
        machine.avgConsumptionLh = body.avgConsumptionLh
        machine.operatorId = body.operatorId
        machine.tillageSystems = systems(body.tillageSystems)
        machines.saveAndFlush(machine)
        return ApiResponse.created(reload(machine.id))
    }

    /** Re-reads the row so the operator association matches the saved operatorId. */
    private fun reload(id: UUID): MachineWithOperator {
        val machine = machines.findById(id).orElseThrow()
        entityManager.refresh(machine)
        return MachineWithOperator.of(machine)
    }

    @PatchMapping("/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Update a machine")
    @Transactional
    fun update(
        auth: AuthContext,
        @PathVariable id: UUID,
        @Valid @RequestBody body: MachineUpdate,
    ): ResponseEntity<ApiResponse<MachineWithOperator>> {
        val machine = access.requireMachine(auth.farmId(), id)
        if (body.operatorId.isPresent && body.operatorId.get() != null) {
            access.requireMember(auth.farmId(), body.operatorId.get())
        }
        body.name.ifPresent { machine.name = it }
        body.type.ifPresent { machine.type = it }
        body.brand.ifPresent { machine.brand = it }
        body.model.ifPresent { machine.model = it }
        body.powerHp.ifPresent { machine.powerHp = it }
        body.avgConsumptionLh.ifPresent { machine.avgConsumptionLh = it }
        body.operatorId.ifPresent { machine.operatorId = it }
        body.tillageSystems.ifPresent { machine.tillageSystems = systems(it) }
        machines.saveAndFlush(machine)
        return ApiResponse.ok(reload(machine.id))
    }

    @DeleteMapping("/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Archive a machine")
    @Transactional
    fun archive(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<Void> {
        val machine = access.requireMachine(auth.farmId(), id)
        machine.archivedAt = Times.now()
        machines.save(machine)
        return ApiResponse.noContent()
    }

    private companion object {
        fun systems(list: List<TillageSystem>?): Array<String> = list?.map { it.name }?.toTypedArray() ?: arrayOf()
    }
}
