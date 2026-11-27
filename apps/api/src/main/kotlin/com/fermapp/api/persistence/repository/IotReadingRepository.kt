package com.fermapp.api.persistence.repository

import com.fermapp.api.domain.model.IotDeviceKind
import com.fermapp.api.persistence.entity.IotReading
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface IotReadingRepository : JpaRepository<IotReading, Long?> {

    fun findByDeviceIdOrderByRecordedAtDesc(deviceId: UUID, page: Pageable): List<IotReading>

    fun findByDeviceIdAndMetricOrderByRecordedAtDesc(deviceId: UUID, metric: String, page: Pageable): List<IotReading>

    /** The latest reading of the given kind of sensor on the parcel, since the given moment. */
    @Query("""
            select r from IotReading r join r.device d
            where d.parcelId = :parcelId and d.kind = :kind and r.recordedAt >= :since
            order by r.recordedAt desc
            """)
    fun findLatestOfKind(@Param("parcelId") parcelId: UUID, @Param("kind") kind: IotDeviceKind, @Param("since") since: Instant, page: Pageable): List<IotReading>
}
