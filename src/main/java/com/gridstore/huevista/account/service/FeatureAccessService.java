package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.account.repository.RetailerFeatureAssignmentRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for "which pages may this shop open?".
 *
 * The mirror of {@link BrandAccessService}, one level up: that service answers which
 * paint companies a shop may work with, this one answers which parts of the product
 * it may reach at all. Both restrictions are granted by the shop's distributor and
 * both are stated by an explicit flag on the org rather than inferred from row count.
 *
 * <p>The restriction applies to RETAILER accounts only. Admins, distributors and
 * painters are never limited by it — a distributor is the one doing the granting, and
 * painters/customers already have their own (role-based) surface. Asking about a user
 * who has no shop therefore returns "unrestricted" rather than failing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureAccessService {

    private final OrganizationRepository orgRepository;
    private final RetailerFeatureAssignmentRepository featureAssignmentRepository;
    private final UserRepository userRepository;

    /**
     * The pages a shop may open.
     *
     * @return empty when the shop is unrestricted (every page); otherwise the exact
     *         set granted — which may legitimately be EMPTY, meaning the distributor
     *         has switched every optional page off.
     */
    @Transactional(readOnly = true)
    public Optional<Set<AppFeature>> allowedFeatures(String retailerOrgId) {
        Organization org = orgRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + retailerOrgId));
        if (!org.isFeaturesRestricted()) {
            return Optional.empty();
        }
        Set<AppFeature> granted = EnumSet.noneOf(AppFeature.class);
        featureAssignmentRepository.findByRetailerId(retailerOrgId)
                .forEach(a -> granted.add(a.getFeature()));
        return Optional.of(granted);
    }

    /**
     * The pages a signed-in user may open, resolved through their shop.
     *
     * Anyone who is not a retailer — or a retailer whose org was never provisioned —
     * comes back unrestricted: this restriction is a distributor→shop tool, and
     * accidentally applying it to an admin would lock the platform's own operators out.
     */
    @Transactional(readOnly = true)
    public Optional<Set<AppFeature>> allowedFeaturesForUser(String userId) {
        return retailerOrgOf(userId).flatMap(org -> allowedFeatures(org.getId()));
    }

    /** True when the user may open {@code feature} (always true for non-retailers). */
    @Transactional(readOnly = true)
    public boolean canUse(String userId, AppFeature feature) {
        return allowedFeaturesForUser(userId).map(f -> f.contains(feature)).orElse(true);
    }

    /**
     * Guard for an endpoint that backs a grantable page. Throws {@link SecurityException}
     * — which the app's handler renders as 403 — naming the page, so the shop knows what
     * to ask its distributor for rather than seeing a bare "forbidden".
     */
    @Transactional(readOnly = true)
    public void assertFeature(String userId, AppFeature feature) {
        if (!canUse(userId, feature)) {
            throw new SecurityException("\"" + feature.getLabel() + "\" isn't switched on for your shop. "
                    + "Ask your distributor to enable it.");
        }
    }

    /** The caller's shop org, if they are a retailer who has one. */
    @Transactional(readOnly = true)
    public Optional<Organization> retailerOrgOf(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getRole() != UserRole.RETAILER) {
            return Optional.empty();
        }
        return orgRepository.findByOwnerIdAndType(userId, OrgType.RETAILER).stream().findFirst();
    }
}
