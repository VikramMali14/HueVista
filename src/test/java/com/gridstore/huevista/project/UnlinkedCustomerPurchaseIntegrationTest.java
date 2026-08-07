package com.gridstore.huevista.project;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.ProjectCredit;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.razorpay.RazorpayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The account with nothing behind it: a CUSTOMER who signed up on their own, holds no
 * access code, belongs to no shop, and cannot buy a plan.
 *
 * This is the one route into the product that has no shop in it anywhere, and it used to
 * end in a refusal that named a shop the account does not have ("ask your paint shop for
 * a code"). What has to hold: the refusal points at the purchase that actually exists,
 * paying for one lands a credit even with no subscription to put it on, and the credit
 * creates a project with the window it was bought for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class UnlinkedCustomerPurchaseIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectCreditService projectCreditService;
    @Autowired PasswordEncoder passwordEncoder;

    private String token;
    private String userId;
    private String imageId;

    @BeforeEach
    void setUp() throws Exception {
        // Deliberately bare: no org membership, no entitlement row, no subscription. This
        // is somebody who found HueVista themselves rather than through a counter.
        User customer = userRepository.save(User.builder()
                .name("Walk-up Customer")
                .email("onmyown@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.CUSTOMER)
                .emailVerified(true)
                .build());
        userId = customer.getId();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"onmyown@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        token = objectMapper.readValue(login.getResponse().getContentAsString(), AuthResponse.class)
                .getAccessToken();

        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(customer)
                .originalFilename("my-room.jpg")
                .storageKey("test/my-room.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build());
        imageId = image.getId();
    }

    /**
     * The refusal has to name a route this account can actually take. "Ask your paint
     * shop" is not one — there is no shop — so it quotes the price of the thing they can
     * buy, and mentions a code only as the alternative for someone who has since been
     * given one.
     */
    @Test
    void withNothingBehindTheAccountTheRefusalOffersThePurchase() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + imageId + "\",\"name\":\"My bedroom\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "₹" + Plan.FREE.extraProjectPriceWithTaxInPaise() / 100)))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("days")));

        assertThat(projectRepository.countByUserId(userId)).isZero();
    }

    /**
     * The price is quoted at the FREE tier's rate and the points rail is shut, which is
     * the pair the buy screen reads: it hides the points button on the second and shows
     * the card at the first.
     */
    @Test
    void purchaseOptionsQuoteTheNoPlanRateAndCloseThePointsRail() throws Exception {
        mockMvc.perform(get("/api/billing/points/project-options")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricingPlan").value("FREE"))
                .andExpect(jsonPath("$.projectPricePaise")
                        .value(Plan.FREE.extraProjectPriceWithTaxInPaise()))
                .andExpect(jsonPath("$.pointsEligible").value(false))
                .andExpect(jsonPath("$.availableCredits").value(0));
    }

    /**
     * A paid project lands in the standalone ledger — there is no subscription to add it
     * to — and creating with it works, with the validity window the purchase opened.
     *
     * The credit is issued through the same service the verified Razorpay callback uses,
     * so this exercises the landing point rather than a fixture shaped like one.
     */
    @Test
    void aBoughtProjectLandsInTheLedgerAndCreatesARoom() throws Exception {
        projectCreditService.creditPurchasedProject(userId, ProjectCredit.Source.PURCHASE);

        mockMvc.perform(get("/api/billing/points/project-options")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCredits").value(1));

        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + imageId + "\",\"name\":\"My bedroom\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My bedroom"));

        assertThat(projectRepository.countByUserId(userId)).isEqualTo(1);

        // Bought outright with no plan running, so the window it was bought for is
        // ticking rather than banked behind a subscription that would have covered it.
        // (The create response doesn't carry access fields — they are attached on the
        // detail read — so this is asserted against the stored project.)
        var created = projectRepository.findAll().stream()
                .filter(p -> userId.equals(p.getUser().getId())).findFirst().orElseThrow();
        assertThat(created.getAccessExpiresAt()).isNotNull().isAfter(java.time.LocalDateTime.now());
        assertThat(created.getAccessPausedAt()).isNull();

        // One purchase, one project: the credit is spent, not reusable.
        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + imageId + "\",\"name\":\"My kitchen\"}"))
                .andExpect(status().isPaymentRequired());
    }
}
