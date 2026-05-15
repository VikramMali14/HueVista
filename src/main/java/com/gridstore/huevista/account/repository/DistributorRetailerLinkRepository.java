package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.DistributorRetailerLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DistributorRetailerLinkRepository extends JpaRepository<DistributorRetailerLink, Long> {

    List<DistributorRetailerLink> findByDistributorId(String distributorId);

    List<DistributorRetailerLink> findByRetailerId(String retailerId);

    Optional<DistributorRetailerLink> findByDistributorIdAndRetailerId(String distributorId, String retailerId);

    boolean existsByDistributorIdAndRetailerId(String distributorId, String retailerId);
}
