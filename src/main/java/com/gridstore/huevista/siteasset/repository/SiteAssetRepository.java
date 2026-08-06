package com.gridstore.huevista.siteasset.repository;

import com.gridstore.huevista.siteasset.model.SiteAsset;
import org.springframework.data.jpa.repository.JpaRepository;

/** Slots are the key, so the JPA defaults are the whole interface. */
public interface SiteAssetRepository extends JpaRepository<SiteAsset, String> {
}
