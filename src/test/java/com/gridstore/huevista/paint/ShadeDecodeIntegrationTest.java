package com.gridstore.huevista.paint;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.OrgMembership;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The shop's decoder: a customer's HV code in, the real colour out — and, for a
 * company the shop actually stocks, the nearest shade in its range.
 *
 * The access rule is the feature, not a detail around it. An HV code is a row number
 * that gives nothing away, which is what makes it safe to print on a colour board and
 * forward in a link; the exchange is that reading one back requires a HueVista shop
 * account. If the endpoint ever opened up, every printed board in circulation would
 * become self-decoding.
 *
 * HV codes are assigned by a Postgres column DEFAULT in production (V54). The test
 * suite runs H2 with Flyway off and no such default, so they are set explicitly here —
 * which is precisely why the entity leaves the column writable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ShadeDecodeIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;
    @MockitoBean com.gridstore.huevista.project.queue.SegmentationJobQueue segmentationJobQueue;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository orgRepository;
    @Autowired OrgMembershipRepository membershipRepository;
    @Autowired BrandRepository brandRepository;
    @Autowired ShadeRepository shadeRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String shopToken;
    private String customerToken;

    @BeforeEach
    void setUp() throws Exception {
        User retailer = userRepository.save(User.builder()
                .name("Shop Owner").email("shop-decode@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).role(UserRole.RETAILER).emailVerified(true).build());
        Organization org = orgRepository.save(Organization.builder()
                .name("Decode Paints").slug("decode-paints-test")
                .type(OrgType.RETAILER).owner(retailer).build());
        membershipRepository.save(OrgMembership.builder()
                .user(retailer).organization(org).role(OrgMemberRole.OWNER).build());
        shopToken = login("shop-decode@example.com", "password123");

        userRepository.save(User.builder()
                .name("Walk-in").email("customer-decode@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).role(UserRole.CUSTOMER).emailVerified(true).build());
        customerToken = login("customer-decode@example.com", "password123");

        Brand alpha = brandRepository.save(Brand.builder().name("Alpha Paints").slug("alpha-decode").build());
        Brand beta = brandRepository.save(Brand.builder().name("Beta Paints").slug("beta-decode").build());

        // The colour on the customer's board.
        shadeRepository.save(Shade.builder().brand(alpha).shadeCode("A100").hvCode("HV9001")
                .name("terracotta dusk").hexCode("#B8734A").shadeFamily("earths").build());
        // Beta carries the very same colour — an exact match is possible.
        shadeRepository.save(Shade.builder().brand(beta).shadeCode("B200").hvCode("HV9002")
                .name("clay evening").hexCode("#B8734A").shadeFamily("earths").build());
        // …and a near miss, plus something nowhere near, so "nearest" has to choose.
        shadeRepository.save(Shade.builder().brand(beta).shadeCode("B201").hvCode("HV9003")
                .name("almost clay").hexCode("#BA7550").shadeFamily("earths").build());
        shadeRepository.save(Shade.builder().brand(beta).shadeCode("B202").hvCode("HV9004")
                .name("deep sea").hexCode("#123456").shadeFamily("blues").build());
    }

    @Test
    void shop_reads_an_hv_code_back_to_the_real_shade() throws Exception {
        mockMvc.perform(get("/api/shades/decode").param("code", "HV9001")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedBy").value("HV_CODE"))
                .andExpect(jsonPath("$.shade.shadeCode").value("A100"))
                .andExpect(jsonPath("$.shade.name").value("terracotta dusk"))
                .andExpect(jsonPath("$.shade.brandName").value("Alpha Paints"));
    }

    /** Lower case off a phone screen must read the same as upper case off a board. */
    @Test
    void hv_codes_are_case_insensitive() throws Exception {
        mockMvc.perform(get("/api/shades/decode").param("code", "hv9001")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shade.shadeCode").value("A100"));
    }

    /**
     * The second field, and the reason the converter is on the dashboard: the shop
     * stocks Beta, the customer designed against Alpha, and Beta happens to carry the
     * identical colour. That must be reported as EXACT — quoting it as an
     * approximation would send the customer away for no reason.
     */
    @Test
    void matching_into_a_company_that_carries_the_colour_reports_exact() throws Exception {
        mockMvc.perform(get("/api/shades/decode")
                        .param("code", "HV9001").param("brand", "beta-decode")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandMatch.brandName").value("Beta Paints"))
                .andExpect(jsonPath("$.brandMatch.shade.shadeCode").value("B200"))
                .andExpect(jsonPath("$.brandMatch.exact").value(true))
                .andExpect(jsonPath("$.brandMatch.deltaE").value(0.0));
    }

    /**
     * And the other half of that pair: when the company has no such colour, the
     * nearest one comes back flagged as NOT exact. Confusing the two is how a customer
     * ends up with a wall that is not the colour they chose.
     */
    @Test
    void matching_into_a_company_without_the_colour_reports_the_closest() throws Exception {
        // B200 is removed, so B201 (a near miss) becomes Beta's best answer.
        shadeRepository.delete(shadeRepository.findByHvCode("HV9002").orElseThrow());
        shadeRepository.flush();

        mockMvc.perform(get("/api/shades/decode")
                        .param("code", "HV9001").param("brand", "beta-decode")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandMatch.shade.shadeCode").value("B201"))
                .andExpect(jsonPath("$.brandMatch.exact").value(false))
                .andExpect(jsonPath("$.brandMatch.closeness").isNotEmpty());
    }

    /** A manufacturer's own code works too — the counter types both into one box. */
    @Test
    void a_manufacturer_code_resolves_when_only_one_company_uses_it() throws Exception {
        mockMvc.perform(get("/api/shades/decode").param("code", "A100")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedBy").value("SHADE_CODE"))
                .andExpect(jsonPath("$.shade.hvCode").value("HV9001"));
    }

    /**
     * Shared manufacturer codes come back as a question, never a guess. Naming one
     * company would quote a real shade from the wrong manufacturer — which reads
     * exactly like a correct answer, and is the worst failure this endpoint can have.
     */
    @Test
    void a_manufacturer_code_two_companies_share_is_left_ambiguous() throws Exception {
        Brand gamma = brandRepository.save(Brand.builder().name("Gamma Paints").slug("gamma-decode").build());
        shadeRepository.save(Shade.builder().brand(gamma).shadeCode("A100").hvCode("HV9005")
                .name("rival terracotta").hexCode("#B87350").build());
        shadeRepository.flush();

        mockMvc.perform(get("/api/shades/decode").param("code", "A100")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shade").doesNotExist())
                .andExpect(jsonPath("$.candidates.length()").value(2));
    }

    /** Nothing found is an empty answer, not a 404 — the box is live as you type. */
    @Test
    void an_unknown_code_returns_no_match_rather_than_an_error() throws Exception {
        mockMvc.perform(get("/api/shades/decode").param("code", "HV0000ZZ")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("HV0000ZZ"))
                .andExpect(jsonPath("$.shade").doesNotExist())
                .andExpect(jsonPath("$.matchedBy").doesNotExist());
    }

    /**
     * The rule the whole scheme rests on. A customer holding the code cannot read it,
     * or the board they carry would decode itself.
     */
    @Test
    void a_customer_cannot_decode() throws Exception {
        mockMvc.perform(get("/api/shades/decode").param("code", "HV9001")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    /** And neither can a passer-by. The rest of /api/shades is public; this is not. */
    @Test
    void an_anonymous_visitor_cannot_decode() throws Exception {
        mockMvc.perform(get("/api/shades/decode").param("code", "HV9001"))
                .andExpect(status().isUnauthorized());
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }
}
