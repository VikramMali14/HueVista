package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.Shade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShadeRepository extends JpaRepository<Shade, Long> {

    List<Shade> findByBrandSlugOrderByPopularityAsc(String brandSlug);

    Optional<Shade> findByBrandSlugAndShadeCode(String brandSlug, String shadeCode);

    boolean existsByBrandIdAndShadeCode(Long brandId, String shadeCode);

    @Query("""
            SELECT s FROM Shade s
            WHERE (:brand IS NULL OR s.brand.slug = :brand)
              AND (:family IS NULL OR LOWER(s.shadeFamily) = LOWER(:family))
              AND (:temperature IS NULL OR LOWER(s.colorTemperature) = LOWER(:temperature))
              AND (:tonality IS NULL OR LOWER(s.tonality) = LOWER(:tonality))
              AND (:search IS NULL OR LOWER(s.name) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR s.shadeCode = :search)
            ORDER BY s.popularity ASC NULLS LAST
            """)
    List<Shade> findWithFilters(
            @Param("brand") String brand,
            @Param("family") String family,
            @Param("temperature") String temperature,
            @Param("tonality") String tonality,
            @Param("search") String search
    );

    @Query("SELECT DISTINCT s.shadeFamily FROM Shade s WHERE s.brand.slug = :brandSlug AND s.shadeFamily IS NOT NULL ORDER BY s.shadeFamily")
    List<String> findDistinctFamiliesByBrandSlug(@Param("brandSlug") String brandSlug);
}
