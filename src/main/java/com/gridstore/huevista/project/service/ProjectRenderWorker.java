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

    // ── What each tier runs on ──────────────────────────────────────────────
    //
    // A render is sold at one of two qualities, and a quality IS a model: PREMIUM is the
    // clear, honest picture, LUXURY is a better model at a bigger size, and the price
    // difference between them is a real cost difference rather than a fence. There used to
    // be a third above both — four credits, 4K — and nobody chose it; it is gone, and the
    // dearest thing on sale is now the tier below it. Which model sits in which tier is
    // configuration on purpose — a better one can be promoted without a migration and
    // without a deploy — but the SHAPE is fixed here: a primary that is asked first, and
    // one fallback for when it is merely out of capacity.
    //
    // FLUX.2 leads every tier and Nano Banana backs it up. They are different families with
    // different failure weather, which is the entire point of a fallback: a second model
    // from the same family tends to be busy at the same moments as the first. Both take a
    // LIST of images under their own key, which this call needs — the cleaned photo first,
    // the region masks after it. Flux Kontext is deliberately absent from every tier: it
    // edits exactly one image, so it would silently drop the masks and ignore "keep the
    // original borders".

    @Value("${replicate.render.quality.premium.model:black-forest-labs/flux-2-klein}")
    private String premiumModel;

    @Value("${replicate.render.quality.premium.fallback-models:google/nano-banana}")
    private String premiumFallbacks;

    @Value("${replicate.render.quality.premium.resolution:1K}")
    private String premiumResolution;

    @Value("${replicate.render.quality.luxury.model:black-forest-labs/flux-2-pro}")
    private String luxuryModel;

    @Value("${replicate.render.quality.luxury.fallback-models:google/nano-banana-pro}")
    private String luxuryFallbacks;

    @Value("${replicate.render.quality.luxury.resolution:2K}")
    private String luxuryResolution;

    /**
     * A last resort under every tier, empty by default.
     *
     * <p>It exists so an operator whose whole primary family is down can add a model in one
     * environment variable rather than in a deploy. Empty by default because a silent third
     * choice is not a thing to have running unnoticed: a tier is a promise about which model
     * made the picture, and the fewer ways that promise is quietly broken, the better.
     */
    @Value("${replicate.render.fallback-models:}")
    private String sharedFallbacks;

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
        Tier tier = tierFor(job.quality());
        // The input keys differ per model family, so the body is built per model down in
        // ReplicatePredictions rather than assembled here for one of them. The resolution
        // travels in Nano Banana's 1K/2K/4K units and is translated per family there, so a
        // FLUX primary and a Nano Banana fallback both get a size they understand.
        return replicate.run(new ReplicatePredictions.Ask(
                tier.chain(),
                job.prompt(),
                job.imageUrls(),
                tier.resolution(),
                aspectRatio,
                "jpg"), "Render[" + job.quality() + "]");
    }

    /** One tier's model chain and the size it renders at. */
    private record Tier(java.util.List<String> chain, String resolution) {}

    /**
     * The chain for a tier: its primary, its own fallback, then the shared last resort.
     *
     * <p>Null reads as PREMIUM. A render row written before the tiers existed carries no
     * quality, and the cheapest tier is the honest reading of one that was charged a single
     * credit.
     */
    private Tier tierFor(com.gridstore.huevista.project.model.ProjectRender.Quality quality) {
        return switch (quality == null
                ? com.gridstore.huevista.project.model.ProjectRender.Quality.PREMIUM
                : quality) {
            case PREMIUM -> new Tier(chain(premiumModel, premiumFallbacks), premiumResolution);
            case LUXURY -> new Tier(chain(luxuryModel, luxuryFallbacks), luxuryResolution);
        };
    }

    private java.util.List<String> chain(String primary, String fallbacks) {
        java.util.List<String> chain = new java.util.ArrayList<>(
                ReplicatePredictions.chainOf(primary, fallbacks));
        chain.addAll(ReplicatePredictions.chainOf(null, sharedFallbacks));
        return chain;
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
