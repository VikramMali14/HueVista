package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {
    Optional<Brand> findBySlug(String slug);
    List<Brand> findAllByOrderByNameAsc();
}
