package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.model.DistributorRetailerLink;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.DistributorRetailerLinkRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Puts shops that predate the house distributor into the network.
 *
 * Shops used to be creatable with no distributor at all, so every one an admin set up
 * directly is sitting outside every downline: a stray root in the network report, with
 * nobody answerable for it. Creation no longer allows that and neither does unlinking,
 * but the rule is only true of new rows until the old ones are brought in line — and a
 * guarantee that holds for some of the data is not a guarantee.
 *
 * Runs at startup rather than in the Flyway migration because the house organization
 * has to be built from the {@link Organization} entity's own defaults. Three NOT NULL
 * columns have been added to that table since it was created; a hand-written INSERT
 * would have to be revisited for each one, and would fail on boot if it ever wasn't.
 *
 * Idempotent and cheap: one query for shops with no link, and on every boot after the
 * first it finds none and does nothing. Ordered after {@code AdminSeeder} (the default
 * order) so a first boot that seeds the admin can still provision the house org, which
 * needs an owner.
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class ShopNetworkBackfill implements ApplicationRunner {

    private final OrganizationRepository orgRepository;
    private final DistributorRetailerLinkRepository linkRepository;
    private final HouseDistributorService houseDistributorService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Organization> unlinked = shopsWithNoDistributor();
        if (unlinked.isEmpty()) {
            return;
        }
        Optional<Organization> house = houseDistributorService.ensureHouseOrg();
        if (house.isEmpty()) {
            // No admin account to own it. Nothing is broken — these shops behave exactly
            // as they did before — so log it and let the next boot try again.
            log.warn("{} shop(s) have no distributor and the house distributor cannot be "
                    + "provisioned without an admin account. They stay outside the network for now.",
                    unlinked.size());
            return;
        }
        for (Organization shop : unlinked) {
            linkRepository.save(DistributorRetailerLink.builder()
                    .distributor(house.get())
                    .retailer(shop)
                    .build());
        }
        log.info("Backfill: {} shop(s) with no distributor moved to the house distributor", unlinked.size());
    }

    /** Retailer orgs with no link at all. Two queries, no per-shop lookup. */
    private List<Organization> shopsWithNoDistributor() {
        Set<String> linked = new HashSet<>();
        for (DistributorRetailerLink link : linkRepository.findAll()) {
            linked.add(link.getRetailer().getId());
        }
        return orgRepository.findAll().stream()
                .filter(o -> o.getType() == OrgType.RETAILER)
                .filter(o -> !linked.contains(o.getId()))
                .toList();
    }
}
