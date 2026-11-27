package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.WeatherData
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WeatherDataRepository : JpaRepository<WeatherData, UUID> {

    fun findByParcelIdAndDate(parcelId: UUID, date: LocalDate): Optional<WeatherData>
}
