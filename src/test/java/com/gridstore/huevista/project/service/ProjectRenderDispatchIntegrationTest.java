package com.gridstore.huevista.project.service;

import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.dto.CreateRenderRequest;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectPdfPage;
import com.gridstore.huevista.project.model.ProjectRender;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.repository.ProjectPdfPageRepository;
import com.gridstore.huevista.project.repository.ProjectRenderRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.razorpay.RazorpayClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * What has to be true between accepting a render and something being able to run it.
 *
 * <p>Deliberately NOT {@code @Transactional}, which is the whole point of the class rather
 * than an oversight. Every other test here runs inside a transaction that is rolled back at
 * the end, and under that arrangement the bug this pins is invisible: nothing ever commits,
 * so nothing can be observed to have committed too late. The defect was that the render was
 * handed to the worker thread while the row was still uncommitted — the worker found
 * nothing, logged "Render vanished before it ran", and gave up, leaving the render QUEUED
 * for ever with the customer's allowance spent.
 *
 * <p>Because there is no rollback, each test cleans up what it made.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties")
class ProjectRenderDispatchIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    /** Mocked so no test ever reaches Replicate, and so the dispatch itself is observable. */
    @MockitoBean ProjectRenderWorker worker;

    @Autowired ProjectService projectService;
    @Autowired ProjectRenderService renderService;
    @Autowired ProjectRenderSweeper sweeper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectPdfPageRepository pageRepository;
    @Autowired com.gridstore.huevista.billing.service.AiCreditService aiCreditService;
    @Autowired ProjectRenderRepository renderRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private String userId;
    private String imageId;
    private String projectId;
    private String pageId;
    private String subscriptionId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        String unique = UUID.randomUUID().toString().substring(0, 8);

        User user = userRepository.save(User.builder()
                .name("Render Owner")
                .email("render-" + unique + "@example.com")
                .password("{noop}unused")
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .phoneVerified(true)
                .build());
        userId = user.getId();

        subscriptionId = subscriptionRepository.save(Subscription.builder()
                .user(user)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .projectsUsed(0)
                .projectsLimit(Plan.PROFESSIONAL.getMonthlyProjectLimit())
                .pdfDownloadsUsed(0)
                .pdfDownloadsLimit(Plan.PROFESSIONAL.getMonthlyPdfLimit())
                .pdfImageLimit(Plan.PROFESSIONAL.getPdfImageLimit())
                .build()).getId();

        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(user)
                .originalFilename("room.jpg")
                .storageKey("test/room.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build());
        imageId = image.getId();

        // Closed, because a render is what a finished project produces.
        Project project = projectRepository.save(Project.builder()
                .user(user)
                .image(image)
                .name("Render room")
                .status(ProjectStatus.SEGMENTED)
                .closedAt(LocalDateTime.now())
                .build());
        projectId = project.getId();

        pageId = pageRepository.save(ProjectPdfPage.builder()
                .project(project)
                .boardIndex(1)
                .pageIndex(0)
                .title("Calm")
                .build()).getId();

        // Every AI image is bought with an AI credit — there is no included one on any
        // room any more — so the wallet has to hold enough for the images each test asks
        // for before dispatch is a question at all.
        aiCreditService.grant(userId, 5, "admin", "render dispatch test");
    }

    @AfterEach
    void tearDown() {
        renderRepository.deleteAll(renderRepository.findByProjectIdOrderByCreatedAtDesc(projectId));
        pageRepository.deleteById(pageId);
        projectRepository.deleteById(projectId);
        subscriptionRepository.deleteById(subscriptionId);
        imageRepository.deleteById(imageId);
        userRepository.deleteById(userId);
    }

    private static CreateRenderRequest requestFor(String comboId) {
        CreateRenderRequest request = new CreateRenderRequest();
        request.setComboId(comboId);
        request.setTimeOfDay(ProjectRender.TimeOfDay.DAY);
        request.setBorderMode(ProjectRender.BorderMode.KEEP_ORIGINAL);
        request.setLighting(ProjectRender.Lighting.NATURAL);
        request.setFurnishing(ProjectRender.Furnishing.KEEP);
        request.setStyle(ProjectRender.RenderStyle.MODERN);
        return request;
    }

    /**
     * The regression test.
     *
     * The worker is asked, at the moment it is handed the render, whether that render is
     * readable from another thread — which is the only question that matters, because
     * another thread is exactly where the real worker reads it from. Before the fix the
     * answer was no: the dispatch happened inside the still-open transaction, so the row
     * existed nowhere but in the request thread's own session.
     */
    @Test
    void theWorkerIsNotHandedTheRenderUntilItIsCommitted() {
        AtomicReference<String> dispatched = new AtomicReference<>();
        AtomicBoolean committedWhenDispatched = new AtomicBoolean();

        doAnswer(invocation -> {
            String renderId = invocation.getArgument(0);
            dispatched.set(renderId);
            // Another thread means another connection, so this sees committed rows only —
            // the same view the real worker gets when it opens its transaction.
            committedWhenDispatched.set(CompletableFuture
                    .supplyAsync(() -> renderRepository.existsById(renderId))
                    .join());
            return null;
        }).when(worker).run(anyString());

        var response = projectService.requestRender(userId, projectId, requestFor(pageId));

        assertThat(dispatched.get())
                .as("the render must actually reach the worker")
                .isEqualTo(response.getId());
        assertThat(committedWhenDispatched.get())
                .as("the worker must be able to read the render it was handed")
                .isTrue();
    }

    /** The credit is still spent up front — committing later must not have moved that. */
    @Test
    void theCreditIsSpentWhenTheRenderIsAccepted() {
        projectService.requestRender(userId, projectId, requestFor(pageId));

        assertThat(projectRepository.findById(projectId).orElseThrow().getRendersUsed())
                .isEqualTo(1);
        verify(worker).run(anyString());
    }

    /**
     * A render nothing will ever finish is failed and refunded rather than left QUEUED —
     * the case the old dispatch bug created, and the one a restart creates anyway.
     */
    @Test
    void aStrandedRenderIsFailedAndItsCreditHandedBack() {
        String renderId = projectService.requestRender(userId, projectId, requestFor(pageId)).getId();
        assertThat(projectRepository.findById(projectId).orElseThrow().getRendersUsed()).isEqualTo(1);

        // @CreationTimestamp writes "now" on insert, so age it in the database directly.
        tx.executeWithoutResult(status -> entityManager
                .createQuery("update ProjectRender r set r.createdAt = :when where r.id = :id")
                .setParameter("when", LocalDateTime.now().minusHours(2))
                .setParameter("id", renderId)
                .executeUpdate());

        sweeper.run();

        ProjectRender swept = renderRepository.findById(renderId).orElseThrow();
        assertThat(swept.getStatus()).isEqualTo(ProjectRender.Status.FAILED);
        assertThat(swept.getFailureReason()).contains("credit is back");
        assertThat(projectRepository.findById(projectId).orElseThrow().getRendersUsed())
                .as("a render that never ran must not cost the customer their allowance")
                .isZero();
    }

    /** A render that is merely young is left alone — the sweeper must not refund a live one. */
    @Test
    void aRecentRenderIsLeftAlone() {
        String renderId = projectService.requestRender(userId, projectId, requestFor(pageId)).getId();

        sweeper.run();

        assertThat(renderRepository.findById(renderId).orElseThrow().getStatus())
                .isEqualTo(ProjectRender.Status.QUEUED);
        assertThat(projectRepository.findById(projectId).orElseThrow().getRendersUsed()).isEqualTo(1);
    }

    /**
     * Once the sweeper has given up on a render and refunded it, a worker that shows up
     * late must not start it after all — that would charge for an image already paid back.
     */
    @Test
    void aSweptRenderIsNotStartedByALateWorker() {
        String renderId = projectService.requestRender(userId, projectId, requestFor(pageId)).getId();
        renderService.fail(renderId, "swept");

        assertThat(renderService.startJob(renderId)).isEmpty();
    }
}
