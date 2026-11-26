package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.Income;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncomeRepository extends JpaRepository<Income, UUID> {

    List<Income> findByParcelIdAndYear(UUID parcelId, int year);
}
