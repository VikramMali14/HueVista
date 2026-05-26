package com.gridstore.huevista.painter.service;

import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.painter.dto.PainterProfileResponse;
import com.gridstore.huevista.painter.dto.PainterRetailerLinkResponse;
import com.gridstore.huevista.painter.dto.UpdatePainterProfileRequest;
import com.gridstore.huevista.painter.model.PainterLinkStatus;
import com.gridstore.huevista.painter.model.PainterProfile;
import com.gridstore.huevista.painter.model.PainterRetailerLink;
import com.gridstore.huevista.painter.repository.PainterProfileRepository;
import com.gridstore.huevista.painter.repository.PainterRetailerLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PainterService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PainterProfileRepository profileRepository;
    private final PainterRetailerLinkRepository linkRepository;

    @Transactional(readOnly = true)
    public PainterProfileResponse getMyProfile(String userId) {
        PainterProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No painter profile for user " + userId + ". Redeem an invitation first."));
        return PainterProfileResponse.from(profile);
    }

    @Transactional
    public PainterProfileResponse updateMyProfile(String userId, UpdatePainterProfileRequest req) {
        PainterProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No painter profile for user " + userId + ". Redeem an invitation first."));
        if (req.getPhone() != null) profile.setPhone(req.getPhone());
        if (req.getServiceAreas() != null) profile.setServiceAreas(req.getServiceAreas());
        if (req.getSpecialties() != null) profile.setSpecialties(req.getSpecialties());
        if (req.getYearsExperience() != null) profile.setYearsExperience(req.getYearsExperience());
        if (req.getDayRateInr() != null) profile.setDayRateInr(req.getDayRateInr());
        return PainterProfileResponse.from(profile);
    }

    /** Used internally by InvitationService on redeem to lazily bootstrap a profile. */
    @Transactional
    public PainterProfile ensureProfile(User user) {
        return profileRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    PainterProfile profile = PainterProfile.builder()
                            .user(user)
                            .active(true)
                            .build();
                    if (user.getRole() != UserRole.PAINTER) {
                        user.setRole(UserRole.PAINTER);
                        userRepository.save(user);
                    }
                    log.info("Bootstrapped painter profile for user {}", user.getId());
                    return profileRepository.save(profile);
                });
    }

    /** Active painters linked to a retailer — for assignment dropdowns. */
    @Transactional(readOnly = true)
    public List<PainterProfileResponse> listActivePaintersForRetailer(String retailerOrgId) {
        Organization retailer = organizationRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Retailer org not found: " + retailerOrgId));
        if (retailer.getType() != OrgType.RETAILER) {
            throw new IllegalArgumentException("Organization is not a RETAILER: " + retailerOrgId);
        }
        return linkRepository.findByRetailerIdAndStatus(retailerOrgId, PainterLinkStatus.ACTIVE)
                .stream()
                .map(link -> profileRepository.findByUserId(link.getPainter().getId()).orElse(null))
                .filter(p -> p != null && p.isActive())
                .map(PainterProfileResponse::from)
                .toList();
    }

    /** Retailers this painter currently works with. */
    @Transactional(readOnly = true)
    public List<PainterRetailerLinkResponse> listMyRetailers(String painterUserId) {
        return linkRepository.findByPainterIdAndStatus(painterUserId, PainterLinkStatus.ACTIVE)
                .stream()
                .map(PainterRetailerLinkResponse::from)
                .toList();
    }

    /** Retailer-side: end the relationship (sets status REMOVED — keeps history). */
    @Transactional
    public void removePainterFromRetailer(String requesterUserId, String retailerOrgId, String painterUserId) {
        Organization retailer = organizationRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Retailer org not found: " + retailerOrgId));
        if (!retailer.getOwner().getId().equals(requesterUserId)) {
            throw new SecurityException("Only the retailer owner may remove painters from their org.");
        }
        PainterRetailerLink link = linkRepository.findByPainterIdAndRetailerId(painterUserId, retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No link between painter " + painterUserId + " and retailer " + retailerOrgId));
        link.setStatus(PainterLinkStatus.REMOVED);
        linkRepository.save(link);
    }
}
