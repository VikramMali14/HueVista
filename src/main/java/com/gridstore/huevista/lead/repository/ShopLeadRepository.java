package com.gridstore.huevista.lead.repository;

import com.gridstore.huevista.lead.model.ShopLead;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShopLeadRepository extends JpaRepository<ShopLead, String> {

    List<ShopLead> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Every request ever made from this address — the duplicate check reads all of them. */
    List<ShopLead> findByEmailOrderByCreatedAtDesc(String email);

    /** The newest request from this address in any of the given states. */
    Optional<ShopLead> findFirstByEmailAndStatusInOrderByCreatedAtDesc(
            String email, List<ShopLead.Status> statuses);

    /** Verified requests whose 24-hour deadline has passed — the hourly job's work list. */
    List<ShopLead> findByStatusAndAutoApproveAtBefore(ShopLead.Status status, LocalDateTime before);
}
