package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.PaintLine;
import com.gridstore.huevista.paint.model.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaintLineRepository extends JpaRepository<PaintLine, Long> {
    List<PaintLine> findByBrandIdAndCategoryOrderByQualityTierAscNameAsc(Long brandId, ProductCategory category);
    Optional<PaintLine> findFirstByBrandIdAndCategoryAndNameIgnoreCase(Long brandId, ProductCategory category, String name);
}
