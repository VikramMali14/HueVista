package com.gridstore.huevista.billing;

import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.repository.AiCreditTransactionRepository;
import com.gridstore.huevista.billing.repository.AiCreditWalletRepository;
import com.gridstore.huevista.billing.service.AiCreditService;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.dto.CreateRenderRequest;
import com.gridstore.huevista.project.dto.ProjectRenderResponse;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectPdfPage;
import com.gridstore.huevista.project.model.ProjectRender;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.repository.ProjectPdfPageRepository;
import com.gridstore.huevista.project.repository.ProjectRenderRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.service.ProjectRenderService;
import com.gridstore.huevista.project.service.ProjectRenderWorker;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a render the model refuses hands back — and to which pocket.
 *
 * <p>Deliberately NOT {@code @Transactional}, and that is the whole reason this is a
 * separate class rather than four more methods in {@link AiCreditWalletIntegrationTest}.
 * {@code ProjectRenderService#fail} runs {@code REQUIRES_NEW}, because in production it is
 * called from the worker thread where there is no caller transaction to join. Under a test
 * transaction that suspends the outer one and opens a second that cannot see its
 * uncommitted rows: the render simply is not found, {@code fail} does nothing at all, and
 * every assertion below would report on a refund that never ran.
 *
 * <p>Because there is no rollback, each test cleans up what it made.
 *
 * <p>The rule being pinned: the refund goes back to whichever pocket paid. That has teeth
 * now in a way it did not before credits existed — a room a shop gave away carries NO
 * render allowance, so {@code rendersUsed} is 0 and the old "decrement it" refund would
 * have found nothing to give back and quietly kept the customer's ₹99.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties")
class AiCreditRefundIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    /** Mocked so nothing reaches an image model — these tests fail the render by hand. */
    @MockitoBean ProjectRenderWorker worker;

    @Autowired ProjectRenderService renderService;
    @Autowired AiCreditService aiCreditService;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectPdfPageRepository pageRepository;
    @Autowired ProjectRenderRepository renderRepository;
    @Autowired AiCreditWalletRepository walletRepository;
    @Autowired AiCreditTransactionRepository transactionRepository;

    private String userId;
    private String imageId;
    private String projectId;
    private String pageId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .name("Refund Owner")
                .email("ai-refund-" + UUID.randomUUID() + "@example.com")
                .password("{noop}unused")
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .phoneVerified(true)
                .role(UserRole.CUSTOMER)
                .build());
        userId = user.getId();

        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(user)
                .originalFilename("room.jpg")
                .storageKey("test/room.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build());
        imageId = image.getId();

        pageId = null;
    }

    @AfterEach
    void tearDown() {
        if (projectId != null) {
            renderRepository.deleteAll(renderRepository.findByProjectIdOrderByCreatedAtDesc(projectId));
            if (pageId != null) pageRepository.deleteById(pageId);
            projectRepository.deleteById(projectId);
        }
        transactionRepository.deleteAll(transactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId));
        walletRepository.findByUserId(userId).ifPresent(walletRepository::delete);
        imageRepository.deleteById(imageId);
        userRepository.deleteById(userId);
    }

    @Test
    void aFailedRenderPaidWithACreditHandsTheCreditBack() {
        aiCreditService.grant(userId, 1, "admin", "test");
        // No included render: this is the shape a shop-granted room has.
        Project project = closedProject(0);
        ProjectRenderResponse started = renderService.request(project, renderRequest());
        assertThat(aiCreditService.balance(userId)).isZero();

        renderService.fail(started.getId(), "The model declined this image.");

        assertThat(aiCreditService.balance(userId)).isEqualTo(1);
        assertThat(projectRepository.findById(projectId).orElseThrow().getRendersUsed()).isZero();
    }

    @Test
    void aFailedIncludedRenderHandsTheAllowanceBackAndTouchesNoWallet() {
        aiCreditService.grant(userId, 1, "admin", "test");
        Project project = closedProject(1);
        ProjectRenderResponse started = renderService.request(project, renderRequest());
        assertThat(projectRepository.findById(projectId).orElseThrow().getRendersUsed()).isEqualTo(1);

        renderService.fail(started.getId(), "The model declined this image.");

        assertThat(projectRepository.findById(projectId).orElseThrow().getRendersUsed()).isZero();
        // Untouched: the wallet never paid, so it has nothing to be given.
        assertThat(aiCreditService.balance(userId)).isEqualTo(1);
    }

    @Test
    void theRefundIsVisibleOnTheStatementAsARefundAndNotAsAPurchase() {
        aiCreditService.grant(userId, 1, "admin", "test");
        Project project = closedProject(0);
        ProjectRenderResponse started = renderService.request(project, renderRequest());

        renderService.fail(started.getId(), "The model declined this image.");

        var statement = aiCreditService.recentActivity(userId);
        assertThat(statement.get(0).getType())
                .isEqualTo(com.gridstore.huevista.billing.model.AiCreditTransaction.Type.RENDER_REFUNDED);
        assertThat(statement.get(0).getCredits()).isEqualTo(1);
        assertThat(statement.get(0).getBalanceAfter()).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A closed project with one board page, carrying the given render allowance. */
    private Project closedProject(int rendersAllowed) {
        Project project = projectRepository.save(Project.builder()
                .user(userRepository.findById(userId).orElseThrow())
                .image(imageRepository.findById(imageId).orElseThrow())
                .name("Refund room")
                .status(ProjectStatus.SEGMENTED)
                .closedAt(LocalDateTime.now())
                .rendersAllowed(rendersAllowed)
                .build());
        projectId = project.getId();

        pageId = pageRepository.save(ProjectPdfPage.builder()
                .project(project)
                .boardIndex(1)
                .pageIndex(0)
                .title("Calm")
                .build()).getId();
        return project;
    }

    private CreateRenderRequest renderRequest() {
        CreateRenderRequest request = new CreateRenderRequest();
        request.setComboId(pageId);
        request.setTimeOfDay(ProjectRender.TimeOfDay.DAY);
        request.setBorderMode(ProjectRender.BorderMode.KEEP_ORIGINAL);
        request.setLighting(ProjectRender.Lighting.NATURAL);
        request.setFurnishing(ProjectRender.Furnishing.KEEP);
        request.setStyle(ProjectRender.RenderStyle.MODERN);
        return request;
    }
}
