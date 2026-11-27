package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.UserAccount
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface UserAccountRepository : JpaRepository<UserAccount, UUID> {

    fun findByEmail(email: String): Optional<UserAccount>

    fun existsByEmail(email: String): Boolean
}
