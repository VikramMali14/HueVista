package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.exception.ExternalServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * The minute of HTTP between accepting a render and having an image.
 *
 * It is a separate bean from {@link ProjectRenderService} for a mechanical reason worth
 * stating plainly, because getting it wrong is silent: Spring's {@code @Async} and
 * {@code @Transactional} are implemented with proxies, and a method that calls its own
 * annotated method goes straight down the class and misses the proxy entirely. A worker
 * living on the service would therefore have run synchronously, on the request thread,
 * with none of its transactional boundaries — a customer waiting a minute for a page that
 * was supposed to return immediately, and a status write that could never commit
 * independently of a failure.
 *
 * <p>The service is injected {@code @Lazy} because the two beans reference each other: the
 * service hands work here, and this hands results back. Lazy on one side is what lets
 * Spring build them both.
 */
@Slf4j
@Component
public class ProjectRenderWorker {

    private final ProjectRenderService renderService;
    private final ReplicatePredictions replicate;
    private final StubAiPipeline stubAiPipeline;

    public ProjectRenderWorker(@Lazy ProjectRenderService renderService,
                               ReplicatePredictions replicate,
                               StubAiPipeline stubAiPipeline) {
        this.renderService = renderService;
        this.replicate = replicate;
        this.stubAiPipeline = stubAiPipeline;
    }

    /** Nano Banana Pro, the same model the clean step uses — the best of the ones wired
     *  in at preserving a building's own architecture while repainting it. */
    @Value("${replicate.render.model:google/nano-banana-pro}")
    private String model;

    /**
     * The models tried, in order, once the primary above has been asked its full quota of
     * times and is still out of capacity.
     *
     * <p>This is the difference between "Nano Banana Pro is busy" costing the customer their
     * render and costing them a slightly different picture. The render fails LOUD and has no
     * fallback OUTPUT — the generated image is the whole deliverable, so we never hand back
     * the photo instead — but a different model still produces a real render, so there is
     * nothing to protect the customer from by refusing to ask one.
     *
     * <p>Both entries take a LIST of images under their own key, which the render needs:
     * the cleaned photo comes first and the region masks follow it. Flux Kontext is
     * deliberately absent — it edits exactly one image, so it would silently drop the masks
     * and ignore "keep the original borders".
     */
    @Value("${replicate.render.fallback-models:bytedance/seedream-4,black-forest-labs/flux-2-pro}")
    private String fallbackModels;

    /** 2K by default here, against 1K for the clean: this image is the thing the customer
     *  keeps and shows people, not an intermediate the masks are derived from. */
    @Value("${replicate.render.resolution:2K}")
    private String resolution;

    @Value("${replicate.render.aspect-ratio:match_input_image}")
    private String aspectRatio;

    @Async("aiTaskExecutor")
    public void run(String renderId) {
        try {
            generate(renderId);
        } catch (Exception e) {
            // Anything that escaped generate() is a bug rather than a model failure, but it
            // must still not leave a render RUNNING forever with the allowance spent.
            log.error("Render failed unexpectedly: render={}", renderId, e);
            renderService.fail(renderId, "Something went wrong making your image. "
                    + "Your credit is back — please try again.");
        }
    }

    private void generate(String renderId) {
        // Everything the model needs is read out in one transaction and handed over as
        // plain values: out here there is no session, so a lazy field would throw.
        var job = renderService.startJob(renderId).orElse(null);
        if (job == null) return;

        byte[] image;
        try {
            image = stubAiPipeline.isEnabled() ? stubRender(renderId) : callModel(job);
        } catch (ExternalServiceException e) {
            renderService.fail(renderId, e.getMessage());
            return;
        } catch (Exception e) {
            log.error("Render generation failed: render={}", renderId, e);
            renderService.fail(renderId,
                    "Your image could not be made. Your credit is back — please try again.");
            return;
        }

        try {
            renderService.succeed(renderId, renderService.store(image, job.ownerFolder()));
        } catch (Exception e) {
            log.error("Render produced an image but could not store it: render={}", renderId, e);
            renderService.fail(renderId,
                    "Your image was made but could not be saved. Your credit is back — "
                    + "please try again.");
        }
    }

    private byte[] callModel(ProjectRenderService.RenderJob job) {
        // The input keys differ per model family, so the body is built per model down in
        // ReplicatePredictions rather than assembled here for one of them.
        return replicate.run(new ReplicatePredictions.Ask(
                ReplicatePredictions.chainOf(model, fallbackModels),
                job.prompt(),
                job.imageUrls(),
                resolution,
                aspectRatio,
                "jpg"), "Render");
    }

    /**
     * A flat image standing in for the model, for tests and the free E2E path. Deliberately
     * not a copy of the cleaned photo: a stub that returns something plausible hides the
     * difference between "the render ran" and "the render was skipped".
     */
    private byte[] stubRender(String renderId) {
        log.warn("STUB AI: returning a placeholder render for render={}", renderId);
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
}
