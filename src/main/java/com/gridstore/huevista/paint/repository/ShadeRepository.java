package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.Shade;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShadeRepository extends JpaRepository<Shade, Long>, JpaSpecificationExecutor<Shade> {

    List<Shade> findByBrandSlugOrderByPopularityAsc(String brandSlug);

    Optional<Shade> findByBrandSlugAndShadeCode(String brandSlug, String shadeCode);

    boolean existsByBrandIdAndShadeCode(Long brandId, String shadeCode);

    default List<Shade> findWithFilters(String brand, String family, String temperature, String tonality, String search) {
        return findAll(
                ShadeSpecifications.withFilters(brand, family, temperature, tonality, search),
                Sort.by(Sort.Order.asc("popularity").nullsLast())
        );
    }

    @Query("SELECT DISTINCT s.shadeFamily FROM Shade s WHERE s.brand.slug = :brandSlug AND s.shadeFamily IS NOT NULL ORDER BY s.shadeFamily")
    List<String> findDistinctFamiliesByBrandSlug(@Param("brandSlug") String brandSlug);
}
