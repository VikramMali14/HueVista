package com.gridstore.huevista.library.repository;

import com.gridstore.huevista.library.model.FreeProjectTemplate;
import com.gridstore.huevista.library.model.TemplatePlacement;
import com.gridstore.huevista.library.model.TemplateSpace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FreeProjectTemplateRepository extends JpaRepository<FreeProjectTemplate, String> {

    boolean existsBySlug(String slug);

    Optional<FreeProjectTemplate> findBySlug(String slug);

    /** Admin listing: everything, in gallery order. */
    List<FreeProjectTemplate> findAllByOrderBySpaceAscRoomKeyAscDisplayOrderAscTitleAsc();

    /** What an ordinary visitor would see, once this is opened beyond the admin. */
    List<FreeProjectTemplate> findByPublishedTrueOrderBySpaceAscRoomKeyAscDisplayOrderAscTitleAsc();

    /**
     * One public page's worth of rooms.
     *
     * The caller passes the placements that page accepts — {@code [GALLERY, BOTH]}
     * for the gallery, {@code [WORK, BOTH]} for the portfolio — rather than a
     * single surface, so BOTH needs no special case in the query or in whoever
     * reads it.
     */
    List<FreeProjectTemplate> findByPublishedTrueAndPlacementInOrderBySpaceAscRoomKeyAscDisplayOrderAscTitleAsc(
            Collection<TemplatePlacement> placements);

    /** Fills the "5 per type" counter on the admin page without loading the rows. */
    long countBySpaceAndRoomKey(TemplateSpace space, String roomKey);

    /**
     * Bumped outside the entity so starting a copy never has to load the template
     * for writing — the counter is informational and must not contend with anything.
     */
    @Modifying
    @Query("UPDATE FreeProjectTemplate t SET t.timesUsed = t.timesUsed + 1 WHERE t.id = :id")
    void incrementTimesUsed(@Param("id") String id);
}
