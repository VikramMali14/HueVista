package com.gridstore.huevista.admin;

import com.gridstore.huevista.billing.model.PaymentAttempt;
import com.gridstore.huevista.billing.model.PaymentAttemptStatus;
import com.gridstore.huevista.billing.model.PaymentFlow;
import com.gridstore.huevista.billing.repository.PaymentAttemptRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The payment audit report.
 *
 * <p>Answers the question the application log used to be the only answer to, badly:
 * <em>somebody says they tried to pay — what actually happened?</em> Every checkout this
 * product opens is recorded whether or not money moved, so the report can show the
 * abandoned ones, the declined ones, and the ones where a card was charged and our own
 * verification then failed. Each row carries the page the buyer was on, their IP and
 * browser, the amount they were quoted and the gateway's own error code.
 *
 * <p>Read-only, by design. Nothing here can alter an attempt, refund anything or grant
 * anything — it is evidence, and evidence you can edit is worth less than none.
 */
@RestController
@RequestMapping("/api/admin/payment-audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Super-admin endpoints — ROLE_ADMIN only")
public class PaymentAuditController {

    private final PaymentAttemptRepository attemptRepository;

    /** Same ceiling the rest of the admin console uses; these tables are unbounded. */
    private static final int MAX_PAGE_SIZE = 500;

    /** A CSV pull is for spreadsheets and accountants, so it may be much larger than a page. */
    private static final int MAX_EXPORT_ROWS = 20_000;

    @Operation(summary = "Payment attempts",
            description = "Every checkout opened — paid, abandoned, declined or failed in "
                    + "verification — newest first. Filter by status, flow, user, date range, "
                    + "or free text across buyer e-mail, Razorpay reference, payment id and the "
                    + "page the buyer was on.")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @Parameter(description = "CREATED | OPENED | ABANDONED | FAILED | VERIFY_FAILED | PAID")
            @RequestParam(required = false) String status,
            @Parameter(description = "SUBSCRIPTION | POINTS | PROJECT | REOPEN | STORE_KIOSK")
            @RequestParam(required = false) String flow,
            @Parameter(description = "Exact buyer user id") @RequestParam(required = false) String userId,
            @Parameter(description = "Inclusive start date, yyyy-MM-dd") @RequestParam(required = false) String from,
            @Parameter(description = "Inclusive end date, yyyy-MM-dd") @RequestParam(required = false) String to,
            @Parameter(description = "Free text: e-mail, reference, payment id or page URL")
            @RequestParam(required = false) String q,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 500") @RequestParam(defaultValue = "50") int size) {

        int capped = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        var rows = attemptRepository.search(
                parseStatus(status), parseFlow(flow), blankToNull(userId),
                startOf(from), endOf(to), blankToNull(q),
                Math.max(0, page) * capped, capped);

        return ResponseEntity.ok(rows.stream().map(PaymentAuditController::toRow).toList());
    }

    @Operation(summary = "Payment audit summary",
            description = "Headline counts for the window: attempts by status and by flow, the "
                    + "money that walked away, the pages abandonment happens on, and the gateway's "
                    + "most common decline reasons.")
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @Parameter(description = "How many days back to count. 0 = all time.")
            @RequestParam(defaultValue = "30") int days) {

        LocalDateTime since = days <= 0 ? null : LocalDateTime.now().minusDays(days);

        // Counts by status, with the money each bucket represents. The lost/at-risk
        // figures below are computed from these rather than queried again, so the tiles
        // can never disagree with the breakdown they sit above.
        Map<String, Object> byStatus = new LinkedHashMap<>();
        long total = 0;
        long paidCount = 0;
        long lostPaise = 0;
        long atRiskPaise = 0;
        long abandonedCount = 0;
        long failedCount = 0;
        long verifyFailedCount = 0;

        for (Object[] r : attemptRepository.countByStatusSince(since)) {
            PaymentAttemptStatus s = (PaymentAttemptStatus) r[0];
            long count = ((Number) r[1]).longValue();
            long paise = ((Number) r[2]).longValue();

            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("count", count);
            bucket.put("amountPaise", paise);
            byStatus.put(s.name(), bucket);

            total += count;
            switch (s) {
                case PAID -> paidCount = count;
                case ABANDONED -> { abandonedCount = count; lostPaise += paise; }
                case FAILED -> { failedCount = count; lostPaise += paise; }
                case VERIFY_FAILED -> { verifyFailedCount = count; lostPaise += paise; atRiskPaise += paise; }
                default -> { }
            }
        }

        List<Map<String, Object>> byFlow = new ArrayList<>();
        for (Object[] r : attemptRepository.countByFlowSince(since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            PaymentFlow f = (PaymentFlow) r[0];
            m.put("flow", f.name());
            m.put("displayName", f.getDisplayName());
            m.put("count", ((Number) r[1]).longValue());
            m.put("amountPaise", ((Number) r[2]).longValue());
            byFlow.add(m);
        }

        Pageable topTen = PageRequest.of(0, 10);
        List<Map<String, Object>> worstPages = new ArrayList<>();
        for (Object[] r : attemptRepository.abandonmentByPageSince(since, topTen)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pageUrl", (String) r[0]);
            m.put("count", ((Number) r[1]).longValue());
            m.put("amountPaise", ((Number) r[2]).longValue());
            worstPages.add(m);
        }

        List<Map<String, Object>> failureReasons = new ArrayList<>();
        for (Object[] r : attemptRepository.failureReasonsSince(since, topTen)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("errorCode", (String) r[0]);
            m.put("errorDescription", (String) r[1]);
            m.put("count", ((Number) r[2]).longValue());
            failureReasons.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("windowDays", days <= 0 ? null : days);
        body.put("totalAttempts", total);
        body.put("paidCount", paidCount);
        body.put("abandonedCount", abandonedCount);
        body.put("failedCount", failedCount);
        body.put("verifyFailedCount", verifyFailedCount);
        // Share of FINISHED attempts that were paid. Attempts still in flight are excluded
        // rather than counted as failures, so the number does not dip every time somebody
        // happens to have Checkout open when the report is loaded.
        long finished = paidCount + abandonedCount + failedCount + verifyFailedCount;
        body.put("conversionPercent", finished == 0 ? null
                : Math.round(paidCount * 1000.0 / finished) / 10.0);
        body.put("lostAmountPaise", lostPaise);
        // Money the buyer parted with that bought them nothing. Should always be zero;
        // anything else is an incident with a named buyer attached to it.
        body.put("moneyAtRiskPaise", atRiskPaise);
        body.put("byStatus", byStatus);
        body.put("byFlow", byFlow);
        body.put("worstPages", worstPages);
        body.put("failureReasons", failureReasons);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Export payment attempts as CSV",
            description = "The same rows the report shows, as a spreadsheet — for reconciling "
                    + "against a Razorpay settlement file or handing to an accountant.")
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String flow,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @Parameter(description = "Row cap, max 20000") @RequestParam(defaultValue = "5000") int limit) {

        var rows = attemptRepository.search(
                parseStatus(status), parseFlow(flow), blankToNull(userId),
                startOf(from), endOf(to), blankToNull(q),
                0, Math.min(Math.max(1, limit), MAX_EXPORT_ROWS));

        StringBuilder csv = new StringBuilder(
                "created_at,status,flow,reference,payment_id,buyer,amount_inr,currency,description,"
                + "plan,page_url,ip_address,user_agent,error_code,error_description,error_source,"
                + "error_step,duration_seconds,closed_at\n");
        for (PaymentAttempt a : rows) {
            csv.append(csvCell(a.getCreatedAt())).append(',')
               .append(csvCell(a.getStatus())).append(',')
               .append(csvCell(a.getFlow())).append(',')
               .append(csvCell(a.getReference())).append(',')
               .append(csvCell(a.getPaymentId())).append(',')
               .append(csvCell(a.getUserEmail() != null ? a.getUserEmail() : a.getUserId())).append(',')
               .append(csvCell(String.format("%.2f", a.amountInRupees()))).append(',')
               .append(csvCell(a.getCurrency())).append(',')
               .append(csvCell(a.getDescription())).append(',')
               .append(csvCell(a.getPlan())).append(',')
               .append(csvCell(a.getPageUrl())).append(',')
               .append(csvCell(a.getIpAddress())).append(',')
               .append(csvCell(a.getUserAgent())).append(',')
               .append(csvCell(a.getErrorCode())).append(',')
               .append(csvCell(a.getErrorDescription())).append(',')
               .append(csvCell(a.getErrorSource())).append(',')
               .append(csvCell(a.getErrorStep())).append(',')
               .append(csvCell(a.durationSeconds())).append(',')
               .append(csvCell(a.getClosedAt())).append('\n');
        }

        String filename = "payment-audit-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.toString());
    }

    @Operation(summary = "Every attempt by one buyer",
            description = "The last 50 checkouts this account opened, newest first — the view "
                    + "for working a single support ticket.")
    @GetMapping("/users/{userId}")
    public ResponseEntity<List<Map<String, Object>>> forUser(@PathVariable String userId) {
        return ResponseEntity.ok(attemptRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(PaymentAuditController::toRow).toList());
    }

    // ---- mapping -------------------------------------------------------------------

    /** One attempt, flattened for the console. Everything recorded is exposed — this is
     *  an ADMIN-only forensic view, and a field withheld here is a field that will be
     *  missing on the day somebody needs it. */
    private static Map<String, Object> toRow(PaymentAttempt a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("reference", a.getReference());
        m.put("flow", a.getFlow() == null ? null : a.getFlow().name());
        m.put("flowLabel", a.getFlow() == null ? null : a.getFlow().getDisplayName());
        m.put("status", a.getStatus() == null ? null : a.getStatus().name());
        m.put("statusLabel", a.getStatus() == null ? null : a.getStatus().getDisplayName());
        m.put("moneyAtRisk", a.getStatus() != null && a.getStatus().isMoneyAtRisk());
        m.put("userId", a.getUserId());
        m.put("userEmail", a.getUserEmail());
        m.put("organizationId", a.getOrganizationId());
        m.put("amountPaise", a.getAmountPaise());
        m.put("currency", a.getCurrency());
        m.put("description", a.getDescription());
        m.put("plan", a.getPlan());
        m.put("paymentId", a.getPaymentId());
        m.put("pageUrl", a.getPageUrl());
        m.put("referrer", a.getReferrer());
        m.put("userAgent", a.getUserAgent());
        m.put("ipAddress", a.getIpAddress());
        m.put("errorCode", a.getErrorCode());
        m.put("errorDescription", a.getErrorDescription());
        m.put("errorSource", a.getErrorSource());
        m.put("errorStep", a.getErrorStep());
        m.put("errorReason", a.getErrorReason());
        m.put("failureNote", a.getFailureNote());
        m.put("timeline", a.getTimeline());
        m.put("createdAt", a.getCreatedAt());
        m.put("openedAt", a.getOpenedAt());
        m.put("closedAt", a.getClosedAt());
        m.put("durationSeconds", a.durationSeconds());
        return m;
    }

    // ---- parsing -------------------------------------------------------------------

    /** An unrecognised filter value means "no filter" rather than a 400: these arrive from
     *  a URL an admin may well have typed, and a report is more useful than an error. */
    private static PaymentAttemptStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return PaymentAttemptStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PaymentFlow parseFlow(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return PaymentFlow.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDateTime startOf(String date) {
        LocalDate d = parseDate(date);
        return d == null ? null : d.atStartOfDay();
    }

    /** End dates are INCLUSIVE — "to 6 Aug" has to mean the whole of the 6th, or the last
     *  day of every range an admin types silently goes missing. */
    private static LocalDateTime endOf(String date) {
        LocalDate d = parseDate(date);
        return d == null ? null : d.atTime(23, 59, 59);
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Quote a CSV cell.
     *
     * <p>A leading =, +, - or @ is prefixed with a quote so Excel treats the cell as text.
     * User agents and URLs land in this file unmodified, and a spreadsheet that executes
     * one of them as a formula turns an audit export into a way to attack the person
     * reading it.
     */
    private static String csvCell(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
