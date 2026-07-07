package com.gridstore.huevista.store.repository;

import com.gridstore.huevista.store.model.StoreLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreLinkRepository extends JpaRepository<StoreLink, String> {

    Optional<StoreLink> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<StoreLink> findByOrganizationIdOrderByCreatedAtDesc(String organizationId);
}
