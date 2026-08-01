package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.Brand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {
    Optional<Brand> findBySlug(String slug);
    List<Brand> findAllByOrderByNameAsc();

    /**
     * Companies that actually have shades to sell, by name.
     *
     * <p>The brands table holds more than the catalogue does: a row is created for
     * every seeded product line ({@code PaintLineSeeder}) and every product an admin
     * adds, whether or not that company's shades were ever uploaded. Granting one of
     * those to a shop hands it an empty catalogue, so anything that offers companies
     * to pick from reads this rather than {@link #findAllByOrderByNameAsc()}.
     */
    @Query("SELECT b FROM Brand b WHERE EXISTS (SELECT 1 FROM Shade s WHERE s.brand = b) ORDER BY b.name")
    List<Brand> findWithShadesOrderByNameAsc();
}
