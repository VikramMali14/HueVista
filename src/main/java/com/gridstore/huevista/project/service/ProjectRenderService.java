package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.exception.ExternalServiceException;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.dto.CreateRenderRequest;
import com.gridstore.huevista.project.dto.ProjectRenderResponse;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectPdfPage;
import com.gridstore.huevista.project.model.ProjectRender;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.repository.ProjectRenderRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The last step of a project: one photorealistic AI render of a combination the customer
 * already chose and was handed on paper.
 *
 * Three things make this different from every other AI call in the product, and all three
 * are deliberate.
 *
 * <p><b>It is gated on closing.</b> A render is what a finished job produces, not another
 * tool in the studio. Requiring closure is what keeps the eight combinations meaningful —
 * the customer renders something they committed to, not a forty-first idea.
 *
 * <p><b>It fails LOUD.</b> {@link ImageCleanerService} falls back to the original photo
 * when the model refuses, because a cleaned photo is an improvement on a photo and its
 * absence is survivable. A render has no fallback: the generated image IS the deliverable,
 * so a failure has to be reported, and the allowance handed back.
 *
 * <p><b>The allowance is spent up front and refunded on failure.</b> Reserving first is
 * what stops two browser tabs each starting a ₹99 render on the same included one. It also
 * means the refund path is real code that runs, rather than the "there is no refund
 * endpoint anywhere in the billing module" the colour-board download has to live with —
 * this allowance is a column on the project, so giving it back is a decrement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectRenderService {

    private final ProjectRepository projectRepository;
    private final ProjectRenderRepository renderRepository;
    private final ProjectBoardService boardService;
    private final ReplicatePredictions replicate;
    private final RenderPromptBuilder promptBuilder;
    private final StorageService storageService;
    private final StubAiPipeline stubAiPipeline;

    /** Nano Banana Pro, the same model the clean step uses — the best of the ones wired
     *  in at preserving a building's own architecture while repainting it. */
    @Value("${replicate.render.model:google/nano-banana-pro}")
    private String model;

    /** 2K by default here, against 1K for the clean: this image is the thing the customer
     *  keeps and shows people, not an intermediate the masks are derived from. */
    @Value("${replicate.render.resolution:2K}")
    private String resolution;

    @Value("${replicate.render.aspect-ratio:match_input_image}")
    private String aspectRatio;

    /**
     * Accept a render request: check it may be made, spend the allowance, and hand the
     * work to the AI executor.
     *
     * The allowance is spent inside this transaction, before anything asynchronous starts.
     * Doing it the other way round — start the work, charge when it lands — is what lets a
     * customer with one included render open two tabs and get two.
     */
    @Transactional
    public ProjectRenderResponse request(Project project, CreateRenderRequest request) {
        if (!project.isClosed()) {
            throw new IllegalStateException(
                    "Close this project first — the render is made from the colour boards "
                    + "you hand over.");
        }
        if (!project.hasRenderLeft()) {
            throw new QuotaExceededException(
                    "You've used this project's AI image. Buy another to try a different "
                    + "combination from your colour boards.");
        }
        ProjectPdfPage page = boardService.requirePage(project.getId(), request.getComboId());

        project.setRendersUsed(project.getRendersUsed() + 1);
        projectRepository.save(project);

        ProjectRender render = renderRepository.save(ProjectRender.builder()
                .project(project)
                .page(page)
                .status(ProjectRender.Status.QUEUED)
                .timeOfDay(request.getTimeOfDay())
                .borderMode(request.getBorderMode())
                .lighting(request.getLighting())
                .furnishing(request.getFurnishing())
                .style(request.getStyle())
                .note(request.getNote())
                .build());

        log.info("Render requested: project={} render={} combo={} used={}/{}",
                project.getId(), render.getId(), page.getId(),
                project.getRendersUsed(), project.getRendersAllowed());

        generateAsync(render.getId());
        return ProjectRenderResponse.from(render, null);
    }

    /**
     * Produce the image.
     *
     * Runs on the AI executor and in its own transaction — the caller's has already
     * committed the spent allowance by the time this starts, which is the point: the
     * charge must survive a render that fails, right up until the refund puts it back.
     */
    @Async("aiTaskExecutor")
    public void generateAsync(String renderId) {
        try {
            generate(renderId);
        } catch (Exception e) {
            // Anything that escaped generate() is a bug rather than a model failure, but
            // it must still not leave a render QUEUED forever with the allowance spent.
            log.error("Render failed unexpectedly: render={}", renderId, e);
            fail(renderId, "Something went wrong making your image. Your credit is back — "
                    + "please try again.");
        }
    }

    void generate(String renderId) {
        ProjectRender render = renderRepository.findById(renderId).orElse(null);
        if (render == null) {
            log.warn("Render vanished before it ran: render={}", renderId);
            return;
        }
        Project project = render.getProject();
        markRunning(renderId);

        byte[] image;
        try {
            image = stubAiPipeline.isEnabled()
                    ? stubRender(project)
                    : callModel(render, project);
        } catch (ExternalServiceException e) {
            fail(renderId, e.getMessage());
            return;
        } catch (Exception e) {
            log.error("Render generation failed: render={}", renderId, e);
            fail(renderId, "Your image could not be made. Your credit is back — "
                    + "please try again.");
            return;
        }

        try {
            String ownerId = ownerFolder(project);
            String key = storageService.store(image, ownerId, "render.jpg", "image/jpeg");
            succeed(renderId, key);
            log.info("Render ready: project={} render={}", project.getId(), renderId);
        } catch (Exception e) {
            log.error("Render produced an image but could not store it: render={}", renderId, e);
            fail(renderId, "Your image was made but could not be saved. Your credit is back — "
                    + "please try again.");
        }
    }

    private byte[] callModel(ProjectRender render, Project project) {
        ImageType imageType = project.getImage().getImageType();
        String prompt = promptBuilder.build(render, render.getPage(), imageType);

        List<String> images = new ArrayList<>();
        // The cleaned photo when there is one: clutter gone and every paintable surface
        // flat white, so the model tints a neutral surface instead of fighting the
        // colour that is already there. The original is the fallback, not the default.
        images.add(storageService.getPublicUrl(project.getCleanedImageStorageKey() != null
                ? project.getCleanedImageStorageKey()
                : project.getImage().getStorageKey()));
        if (render.getBorderMode() == ProjectRender.BorderMode.KEEP_ORIGINAL) {
            images.addAll(maskUrls(project));
        }

        Map<String, Object> input = new java.util.HashMap<>();
        input.put("prompt", prompt);
        input.put("image_input", images);
        input.put("output_format", "jpg");
        if (resolution != null && !resolution.isBlank()) input.put("resolution", resolution);
        if (aspectRatio != null && !aspectRatio.isBlank()) input.put("aspect_ratio", aspectRatio);

        return replicate.runToImage(model, input, "Render");
    }

    /**
     * The region masks, so "keep the original borders" means the boundaries this project
     * actually has rather than the model's idea of them.
     *
     * Hand-drawn masks are preferred over generated ones for the same region — a customer
     * who corrected a wall by hand has told us where its edge is, and that answer beats the
     * model's. Regions with no mask are skipped rather than faked.
     */
    private List<String> maskUrls(Project project) {
        return project.getRegions().stream()
                .sorted(java.util.Comparator
                        .comparing(Region::isManual).reversed()
                        .thenComparingInt(Region::getDisplayOrder))
                .map(Region::getMaskUrl)
                .filter(key -> key != null && !key.isBlank())
                .map(storageService::getPublicUrl)
                .toList();
    }

    /** Where a render's bytes live: the owner's folder, or the code's for a guest room. */
    private static String ownerFolder(Project project) {
        if (project.getUser() != null) return project.getUser().getId();
        if (project.getAccessCode() != null) return project.getAccessCode().getId();
        return "orphaned";
    }

    /**
     * A flat image standing in for the model, for tests and the free E2E path. Deliberately
     * not a copy of the cleaned photo: a stub that returns something plausible hides the
     * difference between "the render ran" and "the render was skipped".
     */
    private byte[] stubRender(Project project) {
        log.warn("STUB AI: returning a placeholder render for project={}", project.getId());
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(new java.awt.Color(0x8899AA));
            g.fillRect(0, 0, 64, 64);
            g.dispose();
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new ExternalServiceException("Stub render could not be produced.");
        }
    }

    // ── Status transitions, each in its own transaction ─────────────────────
    //
    // Separate and REQUIRES_NEW because they run from the async worker, where there is no
    // caller transaction to join and a failure that rolls back the status write would
    // leave a render stuck RUNNING with its allowance spent.

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markRunning(String renderId) {
        renderRepository.findById(renderId).ifPresent(r -> {
            r.setStatus(ProjectRender.Status.RUNNING);
            renderRepository.save(r);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void succeed(String renderId, String storageKey) {
        renderRepository.findById(renderId).ifPresent(r -> {
            r.setStatus(ProjectRender.Status.READY);
            r.setStorageKey(storageKey);
            r.setCompletedAt(java.time.LocalDateTime.now());
            renderRepository.save(r);
        });
    }

    /**
     * Record the failure and hand the allowance back.
     *
     * The refund is the important half. A customer who paid ₹99 for a render the model
     * could not produce has to be able to try again without paying twice, and the included
     * render is the same promise — spending it on nothing would make "one render included"
     * a lie in exactly the case where it matters most.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(String renderId, String reason) {
        renderRepository.findById(renderId).ifPresent(r -> {
            r.setStatus(ProjectRender.Status.FAILED);
            r.setFailureReason(reason);
            r.setCompletedAt(java.time.LocalDateTime.now());
            renderRepository.save(r);

            Project project = r.getProject();
            if (project.getRendersUsed() > 0) {
                project.setRendersUsed(project.getRendersUsed() - 1);
                projectRepository.save(project);
                log.info("Render allowance returned after a failure: project={} render={}",
                        project.getId(), renderId);
            }
        });
    }

    // ── Reading ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProjectRenderResponse> list(String projectId) {
        return renderRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(r -> ProjectRenderResponse.from(r, urlFor(r)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectRenderResponse get(String projectId, String renderId) {
        ProjectRender render = renderRepository.findByIdAndProjectId(renderId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Render not found: " + renderId));
        return ProjectRenderResponse.from(render, urlFor(render));
    }

    /** Presigned fresh on every read — the row holds a key, never a URL. */
    private String urlFor(ProjectRender render) {
        return render.getStorageKey() == null ? null
                : storageService.getPublicUrl(render.getStorageKey());
    }
}
