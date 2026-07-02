package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.dto.ShadeBrandSummaryResponse;
import com.gridstore.huevista.paint.model.Shade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShadeRepository extends JpaRepository<Shade, Long>, JpaSpecificationExecutor<Shade> {

    /** Id + hex only — lets the colour matcher scan a 10k+ catalogue without loading full rows. */
    interface ShadeHex {
        Long getId();
        String getHexCode();
    }

    List<Shade> findByBrandSlugOrderByPopularityAsc(String brandSlug);

    Optional<Shade> findByBrandSlugAndShadeCode(String brandSlug, String shadeCode);

    boolean existsByBrandIdAndShadeCode(Long brandId, String shadeCode);

    @Query("SELECT s.shadeCode FROM Shade s WHERE s.brand.id = :brandId")
    List<String> findShadeCodesByBrandId(@Param("brandId") Long brandId);

    default List<Shade> findWithFilters(String brand, String family, String temperature, String tonality, String search) {
        return findAll(
                ShadeSpecifications.withFilters(brand, family, temperature, tonality, search),
                Sort.by(Sort.Order.asc("popularity").nullsLast())
        );
    }

    default Page<Shade> findWithFilters(String brand, String family, String temperature, String tonality,
                                        String search, Pageable pageable) {
        return findAll(ShadeSpecifications.withFilters(brand, family, temperature, tonality, search), pageable);
    }

    @Query("SELECT DISTINCT s.shadeFamily FROM Shade s WHERE s.brand.slug = :brandSlug AND s.shadeFamily IS NOT NULL ORDER BY s.shadeFamily")
    List<String> findDistinctFamiliesByBrandSlug(@Param("brandSlug") String brandSlug);

    /** Companies that actually have shades, with counts — drives dynamic brand pickers. */
    @Query("""
            SELECT new com.gridstore.huevista.paint.dto.ShadeBrandSummaryResponse(b.name, b.slug, COUNT(s))
            FROM Shade s JOIN s.brand b
            GROUP BY b.id, b.name, b.slug
            ORDER BY b.name
            """)
    List<ShadeBrandSummaryResponse> countShadesByBrand();

    List<ShadeHex> findAllProjectedBy();

    List<ShadeHex> findProjectedByBrandSlug(String brandSlug);
}
