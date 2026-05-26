package com.gridstore.huevista.painter.service;

import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.painter.dto.GeneratePainterInvitationRequest;
import com.gridstore.huevista.painter.dto.PainterInvitationResponse;
import com.gridstore.huevista.painter.dto.PainterRetailerLinkResponse;
import com.gridstore.huevista.painter.model.PainterInvitation;
import com.gridstore.huevista.painter.model.PainterLinkStatus;
import com.gridstore.huevista.painter.model.PainterRetailerLink;
import com.gridstore.huevista.painter.repository.PainterInvitationRepository;
import com.gridstore.huevista.painter.repository.PainterRetailerLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PainterInvitationService {

    private static final char[] ALPHANUMERIC =
            "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray(); // no ambiguous 0/O/1/I/L
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_LENGTH = 8;

    private final PainterInvitationRepository invitationRepository;
    private final PainterRetailerLinkRepository linkRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PainterService painterService;

    @Transactional
    public PainterInvitationResponse generate(String requesterUserId,
                                              String retailerOrgId,
                                              GeneratePainterInvitationRequest req) {
        Organization retailer = organizationRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Retailer org not found: " + retailerOrgId));
        if (retailer.getType() != OrgType.RETAILER) {
            throw new IllegalArgumentException("Painter invitations are only for RETAILER organizations.");
        }
        if (!retailer.getOwner().getId().equals(requesterUserId)) {
            throw new SecurityException("Only the retailer owner may generate painter invitations.");
        }

        PainterInvitation invitation = PainterInvitation.builder()
                .code(generateUniqueCode())
                .retailer(retailer)
                .phoneHint(req.getPhoneHint())
                .expiresAt(LocalDateTime.now().plusDays(req.getValidDays()))
                .build();
        invitation = invitationRepository.save(invitation);
        log.info("Painter invitation {} created for retailer {}", invitation.getCode(), retailerOrgId);
        return PainterInvitationResponse.from(invitation);
    }

    @Transactional(readOnly = true)
    public List<PainterInvitationResponse> listForRetailer(String requesterUserId, String retailerOrgId) {
        Organization retailer = organizationRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Retailer org not found: " + retailerOrgId));
        if (!retailer.getOwner().getId().equals(requesterUserId)) {
            throw new SecurityException("Only the retailer owner may list painter invitations.");
        }
        return invitationRepository.findByRetailerIdOrderByCreatedAtDesc(retailerOrgId)
                .stream()
                .map(PainterInvitationResponse::from)
                .toList();
    }

    @Transactional
    public PainterRetailerLinkResponse redeem(String painterUserId, String code) {
        PainterInvitation invitation = invitationRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Invitation code not found: " + code));
        if (invitation.isUsed()) {
            throw new IllegalArgumentException("Invitation code has already been redeemed.");
        }
        if (invitation.isExpired()) {
            throw new IllegalArgumentException("Invitation code has expired.");
        }

        User painter = userRepository.findById(painterUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + painterUserId));

        // Reject if a link already exists in any non-REMOVED state
        linkRepository.findByPainterIdAndRetailerId(painter.getId(), invitation.getRetailer().getId())
                .filter(l -> l.getStatus() != PainterLinkStatus.REMOVED)
                .ifPresent(l -> {
                    throw new IllegalArgumentException(
                            "Painter is already linked to this retailer (status=" + l.getStatus() + ").");
                });

        // Lazily upgrade role + create profile (no-op if already a painter)
        painterService.ensureProfile(painter);

        PainterRetailerLink link = PainterRetailerLink.builder()
                .painter(painter)
                .retailer(invitation.getRetailer())
                .status(PainterLinkStatus.ACTIVE)
                .invitedAt(invitation.getCreatedAt())
                .acceptedAt(LocalDateTime.now())
                .build();
        link = linkRepository.save(link);

        invitation.setUsedAt(LocalDateTime.now());
        invitation.setUsedByPainter(painter);
        invitationRepository.save(invitation);

        log.info("Painter {} redeemed invitation {} from retailer {}",
                painter.getId(), code, invitation.getRetailer().getId());
        return PainterRetailerLinkResponse.from(link);
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = randomCode();
            if (!invitationRepository.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Could not generate a unique painter invitation code after 20 attempts.");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)]);
        }
        return sb.toString();
    }
}
