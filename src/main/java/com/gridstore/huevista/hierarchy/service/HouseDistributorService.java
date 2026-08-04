package com.gridstore.huevista.hierarchy.service;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.OrgMembership;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The house distributor — the platform's own distributor organization, which every
 * shop that has no other one belongs to.
 *
 * <p>Shops used to be creatable with no distributor at all, and an admin who created
 * one directly left it dangling: outside every downline, invisible in the network
 * tree except as a stray root, and with nobody obviously answerable for it. This is
 * the default parent instead. An admin can still file a shop under any real
 * distributor — they pick one on the form — but "none" now means "ours", not
 * "nobody's", and the network report reads as a single tree.
 *
 * <p>It is also what the 24-hour deadline files shops under, since nobody chose one.
 *
 * <p>The org is owned by the platform admin account, created on first use. Owning a
 * DISTRIBUTOR org does not make that account behave like a distributor anywhere —
 * every distributor code path keys off {@code UserRole.DISTRIBUTOR}, which an admin
 * does not have. The one visible consequence is that the admin's role can no longer
 * be changed while it owns the org, which is the same guard every org owner has.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseDistributorService {

    /** Stable slug — this is how the org is found again, so it must never change. */
    public static final String HOUSE_SLUG = "huevista-direct";

    private final OrganizationRepository orgRepository;
    private final OrgMembershipRepository membershipRepository;
    private final UserRepository userRepository;

    @Value("${app.house-distributor.name:HueVista Direct}")
    private String houseName;

    /**
     * The house distributor org, creating it if this is the first shop to need it.
     *
     * <p>Empty only when the platform has no admin account at all (an unseeded
     * deployment) — there would be nobody to own the org. Callers treat that as
     * "no distributor", which is exactly how shop creation behaved before.
     */
    @Transactional
    public Optional<Organization> ensureHouseOrg() {
        Optional<Organization> existing = orgRepository.findBySlug(HOUSE_SLUG);
        if (existing.isPresent()) {
            return existing;
        }
        Optional<User> admin = userRepository.findFirstByRoleOrderByCreatedAtAsc(UserRole.ADMIN);
        if (admin.isEmpty()) {
            log.warn("No ADMIN account, so the house distributor cannot be provisioned — "
                    + "this shop will be created without a distributor.");
            return Optional.empty();
        }
        Organization org = orgRepository.save(Organization.builder()
                .name(houseName)
                .slug(HOUSE_SLUG)
                .type(OrgType.DISTRIBUTOR)
                .owner(admin.get())
                .build());
        membershipRepository.save(OrgMembership.builder()
                .user(admin.get())
                .organization(org)
                .role(OrgMemberRole.OWNER)
                .build());
        log.info("House distributor provisioned: id={} slug={}", org.getId(), HOUSE_SLUG);
        return Optional.of(org);
    }

    /** True for the house org — the admin UI labels it rather than showing it as a company. */
    public boolean isHouse(Organization org) {
        return org != null && HOUSE_SLUG.equals(org.getSlug());
    }
}
