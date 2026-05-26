package com.gridstore.huevista.painter.repository;

import com.gridstore.huevista.painter.model.PainterProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PainterProfileRepository extends JpaRepository<PainterProfile, String> {

    Optional<PainterProfile> findByUserId(String userId);

    List<PainterProfile> findByActiveTrueOrderByRatingDescJobsCompletedDesc();
}
