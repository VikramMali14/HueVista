package com.gridstore.huevista.lead.service;

import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.lead.dto.ShopLeadRequest;
import com.gridstore.huevista.lead.dto.ShopLeadResponse;
import com.gridstore.huevista.lead.model.ShopLead;
import com.gridstore.huevista.lead.repository.ShopLeadRepository;
import com.gridstore.huevista.notification.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopLeadService {

    private final ShopLeadRepository leadRepository;
    private final EmailSender emailSender;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Transactional
    public ShopLeadResponse submit(ShopLeadRequest request) {
        ShopLead lead = leadRepository.save(ShopLead.builder()
                .name(request.getName().trim())
                .email(com.gridstore.huevista.auth.util.Emails.normalize(request.getEmail()))
                .phone(blankToNull(request.getPhone()))
                .shopName(request.getShopName().trim())
                .city(blankToNull(request.getCity()))
                .state(blankToNull(request.getState()))
                .tier(blankToNull(request.getTier()))
                .notes(blankToNull(request.getNotes()))
                .build());
        log.info("Shop lead received: id={} shop={} city={}", lead.getId(), lead.getShopName(), lead.getCity());
        notifyAdmin(lead);
        return ShopLeadResponse.from(lead);
    }

    /** Best-effort heads-up to the admin inbox — a failure never loses the lead. */
    private void notifyAdmin(ShopLead lead) {
        if (adminEmail == null || adminEmail.isBlank()) return;
        try {
            emailSender.send(adminEmail,
                    "New shop account request: " + lead.getShopName(),
                    "A shop asked for a HueVista account.\n\n"
                            + "Shop:   " + lead.getShopName() + "\n"
                            + "Owner:  " + lead.getName() + "\n"
                            + "Email:  " + lead.getEmail() + "\n"
                            + "Phone:  " + (lead.getPhone() != null ? lead.getPhone() : "—") + "\n"
                            + "Place:  " + (lead.getCity() != null ? lead.getCity() : "—")
                            + (lead.getState() != null ? ", " + lead.getState() : "") + "\n"
                            + "Tier:   " + (lead.getTier() != null ? lead.getTier() : "—") + "\n"
                            + (lead.getNotes() != null ? "\nNotes:\n" + lead.getNotes() + "\n" : "")
                            + "\nProvision the account from the admin page, then mark the lead contacted.");
        } catch (Exception e) {
            log.warn("Admin notification for lead {} failed: {}", lead.getId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<ShopLeadResponse> list(int page, int size) {
        return leadRepository.findAllByOrderByCreatedAtDesc(
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)))
                .stream().map(ShopLeadResponse::from).toList();
    }

    @Transactional
    public ShopLeadResponse updateStatus(String leadId, ShopLead.Status status) {
        ShopLead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found: " + leadId));
        lead.setStatus(status);
        leadRepository.save(lead);
        log.info("Shop lead {} marked {}", leadId, status);
        return ShopLeadResponse.from(lead);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
