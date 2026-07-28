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

    /**
     * The inbox that reads shop-account requests.
     *
     * Separate from {@code app.admin.email}, which is the platform admin's LOGIN
     * identity. The two shared one value, so the credentials used to sign into the admin
     * console had to be a mailbox the whole sales side could read. Falls back to the
     * admin address when unset, so a deployment that has not set LEADS_EMAIL yet still
     * gets its leads.
     */
    @Value("${app.leads.email:}")
    private String leadsEmail;

    @Value("${app.admin.email:}")
    private String adminEmail;

    private String leadInbox() {
        return (leadsEmail != null && !leadsEmail.isBlank()) ? leadsEmail : adminEmail;
    }

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
        notifyLeadsInbox(lead);
        return ShopLeadResponse.from(lead);
    }

    /** Best-effort heads-up to the leads inbox — a failure never loses the lead. */
    private void notifyLeadsInbox(ShopLead lead) {
        String inbox = leadInbox();
        if (inbox == null || inbox.isBlank()) return;
        try {
            emailSender.send(inbox,
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
            log.warn("Leads-inbox notification for lead {} failed: {}", lead.getId(), e.getMessage());
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
