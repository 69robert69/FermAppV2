package com.fermapp.api.persistence.repository;

import com.fermapp.api.domain.model.IotDeviceKind;
import com.fermapp.api.persistence.entity.IotReading;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IotReadingRepository extends JpaRepository<IotReading, Long> {

    List<IotReading> findByDeviceIdOrderByRecordedAtDesc(UUID deviceId, Pageable page);

    List<IotReading> findByDeviceIdAndMetricOrderByRecordedAtDesc(UUID deviceId, String metric, Pageable page);

    /** The latest reading of the given kind of sensor on the parcel, since the given moment. */
    @Query("""
            select r from IotReading r join r.device d
            where d.parcelId = :parcelId and d.kind = :kind and r.recordedAt >= :since
            order by r.recordedAt desc
            """)
    List<IotReading> findLatestOfKind(@Param("parcelId") UUID parcelId, @Param("kind") IotDeviceKind kind,
                                      @Param("since") Instant since, Pageable page);
}
