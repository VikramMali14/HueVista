package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.ShopVisibleBrand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ShopVisibleBrandRepository extends JpaRepository<ShopVisibleBrand, Long> {

    /** Pull the brand eagerly — every caller wants its name or slug, never just the row. */
    @Query("select v from ShopVisibleBrand v join fetch v.brand where v.retailer.id = :retailerId")
    List<ShopVisibleBrand> findWithBrandByRetailerId(@Param("retailerId") String retailerId);

    /**
     * Clear a shop's selection so it can be rewritten.
     *
     * Bulk DML rather than the derived {@code deleteByRetailerId}, and the difference is
     * load-bearing. The derived form marks each row for removal in the persistence
     * context, and Hibernate's action queue orders INSERTS BEFORE DELETES — so rewriting
     * a selection that still contains one of the same companies re-inserted
     * {@code (retailer, brand)} while the old row was still there, and the save died on
     * the unique constraint. This executes at the database immediately, before any insert
     * is queued.
     */
    @Modifying
    @Transactional
    @Query("delete from ShopVisibleBrand v where v.retailer.id = :retailerId")
    void deleteByRetailerId(@Param("retailerId") String retailerId);
}
