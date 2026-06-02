package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.ShopProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShopProductRepository extends JpaRepository<ShopProduct, String> {
    List<ShopProduct> findByOrganizationIdOrderByCreatedAtDesc(String organizationId);
    Optional<ShopProduct> findByIdAndOrganizationId(String id, String organizationId);
}
