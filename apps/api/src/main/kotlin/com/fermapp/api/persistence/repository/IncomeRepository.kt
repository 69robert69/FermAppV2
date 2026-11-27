package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.Income
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface IncomeRepository : JpaRepository<Income, UUID> {

    fun findByParcelIdAndYear(parcelId: UUID, year: Int): List<Income>
}
