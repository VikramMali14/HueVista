package com.gridstore.huevista.lead.repository;

import com.gridstore.huevista.lead.model.ShopLead;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShopLeadRepository extends JpaRepository<ShopLead, String> {
    List<ShopLead> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
