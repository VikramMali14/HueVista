package com.gridstore.huevista.store.repository;

import com.gridstore.huevista.store.model.StoreLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreLinkRepository extends JpaRepository<StoreLink, String> {

    /**
     * Any link with this slug, deleted or not.
     *
     * Only for finishing a payment that is already in flight: the money has moved, so
     * the walk-in gets the code they bought even if the shop deleted the link while
     * their Checkout was open. Everything that STARTS something uses the live lookup
     * below.
     */
    Optional<StoreLink> findBySlug(String slug);

    /** A slug the kiosk will still serve — the one to use for anything public. */
    Optional<StoreLink> findBySlugAndDeletedAtIsNull(String slug);

    /** Slugs are unique for all time, deleted ones included: a retired link's URL must
     *  never be handed to a different shop by a later collision. */
    boolean existsBySlug(String slug);

    List<StoreLink> findByOrganizationIdAndDeletedAtIsNullOrderByCreatedAtDesc(String organizationId);
}
