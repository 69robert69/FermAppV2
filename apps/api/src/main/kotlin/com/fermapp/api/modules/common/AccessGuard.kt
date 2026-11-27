package com.fermapp.api.modules.common

import com.fermapp.api.common.error.Errors
import com.fermapp.api.persistence.entity.FarmMembership
import com.fermapp.api.persistence.entity.Machine
import com.fermapp.api.persistence.entity.Parcel
import com.fermapp.api.persistence.repository.FarmMembershipRepository
import com.fermapp.api.persistence.repository.MachineRepository
import com.fermapp.api.persistence.repository.ParcelRepository
import java.util.UUID
import org.springframework.stereotype.Component

/** Every reference received from the client is checked against the farm in the token (lib/access.ts). */
@Component
class AccessGuard(
    private val parcels: ParcelRepository,
    private val machines: MachineRepository,
    private val memberships: FarmMembershipRepository,
) {

    fun requireParcel(farmId: UUID, parcelId: UUID): Parcel =
        parcels.findByIdAndFarmId(parcelId, farmId).orElseThrow { Errors.notFound("Parcel") }

    fun requireActiveParcel(farmId: UUID, parcelId: UUID): Parcel =
        parcels.findByIdAndFarmIdAndArchivedAtIsNull(parcelId, farmId).orElseThrow { Errors.notFound("Parcel") }

    fun requireMachine(farmId: UUID, machineId: UUID): Machine =
        machines.findByIdAndFarmIdAndArchivedAtIsNull(machineId, farmId).orElseThrow { Errors.notFound("Machine") }

    /** The membership with its user loaded. */
    fun requireMember(farmId: UUID, userId: UUID): FarmMembership =
        memberships.findWithUser(farmId, userId).orElseThrow { Errors.notFound("Team member") }
}
