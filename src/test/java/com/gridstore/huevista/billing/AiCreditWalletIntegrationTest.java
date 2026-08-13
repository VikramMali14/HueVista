package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.model.CustomerEntitlement;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.AiCreditService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.dto.CreateProjectRequest;
import com.gridstore.huevista.project.dto.CreateRenderRequest;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectPdfPage;
import com.gridstore.huevista.project.model.ProjectRender;
import com.gridstore.huevista.project.repository.ProjectPdfPageRepository;
import com.gridstore.huevista.project.repository.ProjectRenderRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.service.ProjectRenderService;
import com.gridstore.huevista.project.service.ProjectRenderWorker;
import com.gridstore.huevista.project.service.ProjectService;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who pays for the AI image, now that a room somebody else bought no longer includes one.
 *
 * <p>The rule under test is a single sentence with two halves, and both halves have to hold
 * or the change is worse than not making it. A shop working its OWN room keeps the image it
 * always had — quietly taking that away would be a price rise dressed up as a bug fix. A
 * room a shop GAVE to a customer includes none, because the shop paid for the room out of
 * its monthly quota and nobody paid for the model call at the end of it.
 *
 * <p>The rest is what has to be true around that: the customer can buy the image with
 * credits, spending is a real debit and not a check, and a render the model refuses hands
 * the credit back rather than keeping ₹99 for nothing.
 */
@SpringBootTest
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class AiCreditWalletIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    /** Mocked so no test reaches an image model; the render stays QUEUED, which is all
     *  these assertions are about — the charge happens before the worker is handed it. */
    @MockitoBean ProjectRenderWorker worker;

    @Autowired ProjectService projectService;
    @Autowired ProjectRenderService renderService;
    @Autowired AiCreditService aiCreditService;
    @Autowired PricingService pricingService;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectPdfPageRepository pageRepository;
    @Autowired ProjectRenderRepository renderRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired CustomerEntitlementRepository entitlementRepository;

    private String shopId;
    private String customerId;

    @BeforeEach
    void setUp() {
        User shop = newUser("wallet-shop@example.com", UserRole.RETAILER);
        shopId = shop.getId();
        subscriptionRepository.save(Subscription.builder()
                .user(shop)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(LocalDateTime.now().minusDays(1))
                .currentPeriodEnd(LocalDateTime.now().plusDays(29))
                .projectsUsed(0)
                .projectsLimit(Plan.PROFESSIONAL.getMonthlyProjectLimit())
                .pdfDownloadsUsed(0)
                .pdfDownloadsLimit(Plan.PROFESSIONAL.getMonthlyPdfLimit())
                .pdfImageLimit(Plan.PROFESSIONAL.getPdfImageLimit())
                .build());

        User customer = newUser("wallet-customer@example.com", UserRole.CUSTOMER);
        customerId = customer.getId();
        entitlementRepository.saveAndFlush(CustomerEntitlement.builder()
                .customer(customer)
                .accessExpiresAt(LocalDateTime.now().plusDays(10))
                .projectAllowance(2)
                .projectsCreated(0)
                .build());
    }

    // ── What a new project includes ─────────────────────────────────────────

    @Test
    void aShopsOwnRoomStillIncludesItsAiImage() {
        Project project = projectRepository.findById(createProject(shopId)).orElseThrow();

        assertThat(project.getRendersAllowed()).isEqualTo(1);
        assertThat(project.hasRenderLeft()).isTrue();
    }

    @Test
    void aRoomTheShopGaveAwayIncludesNoAiImage() {
        Project project = projectRepository.findById(createProject(customerId)).orElseThrow();

        // The shop spent a project credit so its customer could try colours and take a
        // colour board away. It did not buy them a Nano Banana Pro call.
        assertThat(project.getRendersAllowed()).isZero();
        assertThat(project.hasRenderLeft()).isFalse();
    }

    // ── Paying for it ───────────────────────────────────────────────────────

    @Test
    void withoutCreditsTheCustomerIsAskedToTopUpRatherThanGivenTheImage() {
        Project project = closedProjectFor(customerId);

        assertThatThrownBy(() -> renderService.request(project, renderRequest(project)))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("AI wallet");

        assertThat(renderRepository.findByProjectIdOrderByCreatedAtDesc(project.getId())).isEmpty();
    }

    @Test
    void aCreditBuysTheImageAndIsActuallyDebited() {
        aiCreditService.grant(customerId, 2, "admin", "test");
        Project project = closedProjectFor(customerId);

        var response = renderService.request(project, renderRequest(project));

        assertThat(response.getId()).isNotBlank();
        assertThat(aiCreditService.balance(customerId)).isEqualTo(1);

        ProjectRender render = renderRepository.findById(response.getId()).orElseThrow();
        assertThat(render.isPaidWithCredit()).isTrue();
        assertThat(render.getPaidByUserId()).isEqualTo(customerId);
        assertThat(render.getCreditsSpent()).isEqualTo(pricingService.aiCreditRenderCost());
    }

    @Test
    void theShopsIncludedImageIsSpentBeforeAnyCreditIs() {
        aiCreditService.grant(shopId, 1, "admin", "test");
        Project project = closedProjectFor(shopId);

        var response = renderService.request(project, renderRequest(project));

        // The included image is the one it already paid for; charging the wallet while it
        // sat unspent would be billing twice for the same picture.
        assertThat(aiCreditService.balance(shopId)).isEqualTo(1);
        ProjectRender render = renderRepository.findById(response.getId()).orElseThrow();
        assertThat(render.isPaidWithCredit()).isFalse();
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getRendersUsed())
                .isEqualTo(1);
    }

    @Test
    void theSecondImageOnAShopsRoomComesOutOfTheWallet() {
        aiCreditService.grant(shopId, 1, "admin", "test");
        Project project = closedProjectFor(shopId);

        renderService.request(project, renderRequest(project));           // the included one
        var second = renderService.request(project, renderRequest(project)); // the bought one

        assertThat(aiCreditService.balance(shopId)).isZero();
        assertThat(renderRepository.findById(second.getId()).orElseThrow().isPaidWithCredit())
                .isTrue();
    }

    // Refunds on a failed render live in AiCreditRefundIntegrationTest, which cannot be
    // @Transactional: ProjectRenderService#fail runs REQUIRES_NEW, so it opens a second
    // transaction that by design cannot see this class's uncommitted rows — it would find
    // no render, do nothing, and the assertion would pass or fail for the wrong reason.

    // ── Who may hold a wallet ───────────────────────────────────────────────

    @Test
    void bothShopsAndCustomersMayHoldCreditsButPaintersMayNot() {
        String painterId = newUser("wallet-painter@example.com", UserRole.PAINTER).getId();

        assertThat(aiCreditService.isEligible(shopId)).isTrue();
        assertThat(aiCreditService.isEligible(customerId)).isTrue();
        assertThat(aiCreditService.isEligible(painterId)).isFalse();

        assertThatThrownBy(() -> aiCreditService.grant(painterId, 1, "admin", "test"))
                .isInstanceOf(SecurityException.class);
    }

    // ── The launch price ────────────────────────────────────────────────────

    @Test
    void theLaunchDiscountHalvesTheListPriceAndScalesWithoutLosingRupees() {
        assertThat(pricingService.aiCreditListPricePaise()).isEqualTo(19_800);
        assertThat(pricingService.aiCreditDiscountPercent()).isEqualTo(50);
        assertThat(pricingService.aiCreditPricePaise()).isEqualTo(9_900);

        // Discounted on the whole order rather than per credit and multiplied, so integer
        // truncation cannot make the button's price differ from the order's.
        assertThat(pricingService.aiCreditPricePaise(3)).isEqualTo(29_700);

        // The two rails for the same picture are priced alike, so topping up in advance
        // can never leave a buyer worse off than paying per project.
        assertThat(pricingService.aiCreditPricePaise()).isEqualTo(pricingService.renderTopUpPricePaise());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private User newUser(String email, UserRole role) {
        return userRepository.saveAndFlush(User.builder()
                .name(role.name().toLowerCase())
                .email(UUID.randomUUID() + "-" + email)
                .password("x")
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .phoneVerified(true)
                .role(role)
                .build());
    }

    /** A project created the ordinary way, so the allowance is decided by the real code. */
    private String createProject(String userId) {
        UploadedImage image = imageRepository.saveAndFlush(UploadedImage.builder()
                .user(userRepository.findById(userId).orElseThrow())
                .originalFilename("room.jpg")
                .storageKey("test/" + UUID.randomUUID() + ".jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .width(1200).height(800)
                .imageType(ImageType.INDOOR)
                .build());

        CreateProjectRequest request = new CreateProjectRequest();
        request.setImageId(image.getId());
        request.setName("Wallet room");
        return projectService.createProject(userId, request).getId();
    }

    /** …then closed, with one board page to render from — a render's two preconditions. */
    private Project closedProjectFor(String userId) {
        Project project = projectRepository.findById(createProject(userId)).orElseThrow();
        project.setClosedAt(LocalDateTime.now());
        projectRepository.saveAndFlush(project);

        pageRepository.saveAndFlush(ProjectPdfPage.builder()
                .project(project)
                .boardIndex(1)
                .pageIndex(1)
                .title("Combination 1")
                .build());
        return project;
    }

    private CreateRenderRequest renderRequest(Project project) {
        String comboId = pageRepository.findByProjectIdWithShades(project.getId())
                .get(0).getId();
        CreateRenderRequest request = new CreateRenderRequest();
        request.setComboId(comboId);
        request.setTimeOfDay(ProjectRender.TimeOfDay.DAY);
        request.setBorderMode(ProjectRender.BorderMode.KEEP_ORIGINAL);
        request.setLighting(ProjectRender.Lighting.NATURAL);
        request.setFurnishing(ProjectRender.Furnishing.KEEP);
        request.setStyle(ProjectRender.RenderStyle.MODERN);
        return request;
    }
}
