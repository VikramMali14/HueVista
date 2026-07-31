package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.RetiredShadeCodeScheme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RetiredShadeCodeSchemeRepository extends JpaRepository<RetiredShadeCodeScheme, String> {

    /** Newest first — the most recently retired pattern is the one most codes in
     *  circulation were printed with. */
    List<RetiredShadeCodeScheme> findByOrganizationIdOrderByRetiredAtDesc(String organizationId);

    boolean existsByOrganizationIdAndPrefixAndInfixAndSuffix(
            String organizationId, String prefix, String infix, String suffix);
}
