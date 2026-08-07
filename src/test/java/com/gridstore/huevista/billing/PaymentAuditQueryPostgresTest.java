package com.gridstore.huevista.billing;

import com.gridstore.huevista.billing.model.PaymentAttempt;
import com.gridstore.huevista.billing.model.PaymentAttemptStatus;
import com.gridstore.huevista.billing.model.PaymentFlow;
import com.gridstore.huevista.billing.repository.PaymentAttemptRepository;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payment audit report's queries, run against a real PostgreSQL.
 *
 * <p>The rest of the suite runs on H2, and H2 will infer a type for a null bind
 * parameter that PostgreSQL refuses to. That gap is not academic: it shipped a report
 * which returned 500 on every load — {@code function lower(bytea) does not exist} and
 * {@code could not determine data type of parameter $1} — while every test passed.
 * The cases below are the ones H2 cannot speak for: each filter left null, which is
 * exactly what the console sends when an admin simply opens the page.
 *
 * <p>Skipped unless a database is pointed at, so the suite still runs with nothing
 * installed. To run it by hand against a local server:
 * <pre>
 * ./mvnw test -Dtest=PaymentAuditQueryPostgresTest \
 *   -Dhuevista.test.postgres.url=jdbc:postgresql://localhost:5432/huevista_test
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        locations = "classpath:application-test.properties",
        properties = {
                "spring.datasource.url=${huevista.test.postgres.url:}",
                "spring.datasource.username=${huevista.test.postgres.username:postgres}",
                "spring.datasource.password=${huevista.test.postgres.password:postgres}",
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                // The real migrations, so every column has the type production gives it.
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=true"
        })
@EnabledIfPostgresConfigured
class PaymentAuditQueryPostgresTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired PaymentAttemptRepository attempts;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void seed() {
        attempts.deleteAll();
        attempts.save(attempt(PaymentAttemptStatus.PAID, PaymentFlow.PROJECT,
                "buyer@example.com", "order_paid_1", "pay_1", "https://huevista.org/checkout"));
        attempts.save(attempt(PaymentAttemptStatus.ABANDONED, PaymentFlow.SUBSCRIPTION,
                "someone@else.test", "order_aband_1", null, "https://huevista.org/pricing"));
        attempts.save(attempt(PaymentAttemptStatus.FAILED, PaymentFlow.POINTS,
                null, "order_failed_1", "pay_3", null));
        attempts.flush();
    }

    /** The plain page load: no status, no flow, no user, no dates, no search term. */
    @Test
    void searchWithNoFiltersReturnsEveryAttempt() {
        List<PaymentAttempt> rows = attempts.search(null, null, null, null, null, null, 0, 50);

        assertThat(rows).extracting(PaymentAttempt::getReference)
                .containsExactlyInAnyOrder("order_paid_1", "order_aband_1", "order_failed_1");
    }

    /** Free text still has to work, and still has to ignore case. */
    @Test
    void searchMatchesFreeTextAcrossFieldsCaseInsensitively() {
        assertThat(attempts.search(null, null, null, null, null, "BUYER@EXAMPLE", 0, 50))
                .extracting(PaymentAttempt::getReference).containsExactly("order_paid_1");
        assertThat(attempts.search(null, null, null, null, null, "order_failed", 0, 50))
                .extracting(PaymentAttempt::getReference).containsExactly("order_failed_1");
        assertThat(attempts.search(null, null, null, null, null, "PAY_1", 0, 50))
                .extracting(PaymentAttempt::getReference).containsExactly("order_paid_1");
        assertThat(attempts.search(null, null, null, null, null, "/pricing", 0, 50))
                .extracting(PaymentAttempt::getReference).containsExactly("order_aband_1");
    }

    /** A term nobody typed as a wildcard must not behave as one. */
    @Test
    void searchTreatsLikeMetacharactersLiterally() {
        assertThat(attempts.search(null, null, null, null, null, "%", 0, 50)).isEmpty();
        assertThat(attempts.search(null, null, null, null, null, "buyer_example", 0, 50)).isEmpty();
    }

    @Test
    void searchCombinesFilters() {
        assertThat(attempts.search(PaymentAttemptStatus.PAID, null, null, null, null, null, 0, 50))
                .extracting(PaymentAttempt::getReference).containsExactly("order_paid_1");
        assertThat(attempts.search(null, PaymentFlow.SUBSCRIPTION, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), null, 0, 50))
                .extracting(PaymentAttempt::getReference).containsExactly("order_aband_1");
        assertThat(attempts.search(PaymentAttemptStatus.PAID, PaymentFlow.SUBSCRIPTION,
                null, null, null, null, 0, 50)).isEmpty();
    }

    @Test
    void searchPagesWithoutRepeatingOrDroppingRows() {
        List<PaymentAttempt> first = attempts.search(null, null, null, null, null, null, 0, 2);
        List<PaymentAttempt> second = attempts.search(null, null, null, null, null, null, 2, 2);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    /** days=0 on the summary endpoint — the all-time window, which binds a null date. */
    @Test
    void summaryAggregatesAcceptTheAllTimeWindow() {
        assertThat(attempts.countByStatusSince(null)).hasSize(3);
        assertThat(attempts.countByFlowSince(null)).hasSize(3);
        assertThat(attempts.abandonmentByPageSince(null, PageRequest.of(0, 10))).hasSize(1);
        assertThat(attempts.failureReasonsSince(null, PageRequest.of(0, 10))).hasSize(1);
    }

    /** ...and the bounded window still has to filter, not merely parse. */
    @Test
    void summaryAggregatesRespectTheWindow() {
        assertThat(attempts.countByStatusSince(LocalDateTime.now().minusDays(30))).hasSize(3);
        assertThat(attempts.countByStatusSince(LocalDateTime.now().plusDays(1))).isEmpty();
    }

    // ---- the endpoints themselves, which is where the 500s were seen ----------------

    /** Opening the report with nothing filled in — the request that used to 500. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void reportLoadsWithNoFilters() throws Exception {
        mockMvc.perform(get("/api/admin/payment-audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reportLoadsWithFilters() throws Exception {
        mockMvc.perform(get("/api/admin/payment-audit")
                        .param("status", "PAID").param("q", "buyer@example"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reference").value("order_paid_1"));
    }

    /** days=0 is the all-time window, and the one that binds a null date. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void summaryLoadsForEveryWindow() throws Exception {
        for (String days : new String[]{"0", "30"}) {
            mockMvc.perform(get("/api/admin/payment-audit/summary").param("days", days))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalAttempts").value(3))
                    .andExpect(jsonPath("$.byStatus.PAID.count").value(1));
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void exportLoadsWithNoFilters() throws Exception {
        mockMvc.perform(get("/api/admin/payment-audit/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("order_paid_1")));
    }

    private static PaymentAttempt attempt(PaymentAttemptStatus status, PaymentFlow flow,
                                          String email, String reference, String paymentId, String pageUrl) {
        PaymentAttempt a = new PaymentAttempt();
        a.setStatus(status);
        a.setFlow(flow);
        a.setUserEmail(email);
        a.setReference(reference);
        a.setPaymentId(paymentId);
        a.setPageUrl(pageUrl);
        a.setUserId("user-" + reference);
        a.setAmountPaise(9900);
        a.setCurrency("INR");
        a.setErrorCode(status == PaymentAttemptStatus.FAILED ? "BAD_CARD" : null);
        a.setErrorDescription(status == PaymentAttemptStatus.FAILED ? "Card declined" : null);
        return a;
    }
}
