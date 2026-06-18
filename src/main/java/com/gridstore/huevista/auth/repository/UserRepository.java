package com.gridstore.huevista.auth.repository;

import com.gridstore.huevista.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /** A user who has VERIFIED this mobile number — the only valid SMS-reset target. */
    Optional<User> findByPhoneNumberAndPhoneVerifiedTrue(String phoneNumber);

    List<User> findTop10ByOrderByCreatedAtDesc();

    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :since")
    long countByCreatedAtAfter(@Param("since") LocalDateTime since);
}
