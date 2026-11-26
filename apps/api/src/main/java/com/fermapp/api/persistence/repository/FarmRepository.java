package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.Farm;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FarmRepository extends JpaRepository<Farm, UUID> {
}
