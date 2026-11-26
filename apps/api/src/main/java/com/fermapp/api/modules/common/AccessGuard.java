package com.fermapp.api.modules.common;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.persistence.entity.FarmMembership;
import com.fermapp.api.persistence.entity.Machine;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.repository.FarmMembershipRepository;
import com.fermapp.api.persistence.repository.MachineRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Every reference received from the client is checked against the farm in the token (lib/access.ts). */
@Component
public class AccessGuard {

    private final ParcelRepository parcels;
    private final MachineRepository machines;
    private final FarmMembershipRepository memberships;

    public AccessGuard(ParcelRepository parcels, MachineRepository machines, FarmMembershipRepository memberships) {
        this.parcels = parcels;
        this.machines = machines;
        this.memberships = memberships;
    }

    public Parcel requireParcel(UUID farmId, UUID parcelId) {
        return parcels.findByIdAndFarmId(parcelId, farmId).orElseThrow(() -> Errors.notFound("Parcel"));
    }

    public Parcel requireActiveParcel(UUID farmId, UUID parcelId) {
        return parcels.findByIdAndFarmIdAndArchivedAtIsNull(parcelId, farmId).orElseThrow(() -> Errors.notFound("Parcel"));
    }

    public Machine requireMachine(UUID farmId, UUID machineId) {
        return machines.findByIdAndFarmIdAndArchivedAtIsNull(machineId, farmId).orElseThrow(() -> Errors.notFound("Machine"));
    }

    /** The membership with its user loaded. */
    public FarmMembership requireMember(UUID farmId, UUID userId) {
        return memberships.findWithUser(farmId, userId).orElseThrow(() -> Errors.notFound("Team member"));
    }
}
