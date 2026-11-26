package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.WeatherData;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeatherDataRepository extends JpaRepository<WeatherData, UUID> {

    Optional<WeatherData> findByParcelIdAndDate(UUID parcelId, LocalDate date);
}
