package com.gridstore.huevista.auth.repository;

import com.gridstore.huevista.auth.model.AdminLoginCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

public interface AdminLoginCodeRepository extends JpaRepository<AdminLoginCode, Long> {

    Optional<AdminLoginCode> findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(String userId);

    /** A fresh login attempt supersedes any earlier outstanding codes. */
    @Modifying
    void deleteByUserId(String userId);
}
