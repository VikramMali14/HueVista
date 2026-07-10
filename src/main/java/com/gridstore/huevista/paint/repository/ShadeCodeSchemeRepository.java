package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.ShadeCodeScheme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShadeCodeSchemeRepository extends JpaRepository<ShadeCodeScheme, String> {

    Optional<ShadeCodeScheme> findByOrganizationId(String organizationId);
}
