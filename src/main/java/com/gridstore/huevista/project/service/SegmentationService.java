package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.exception.ExternalServiceException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.model.HouseType;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.SceneAnalysis;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.model.CleanAngle;
import com.gridstore.huevista.project.model.CleanFurnishing;
import com.gridstore.huevista.project.model.FailureStage;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Segments room and facade images for paint visualization.
 *
 * <h3>Auto flow (segmentAsync)</h3>
 * <ol>
 *   <li>Send the photo to {@link ImageCleanerService} — Nano Banana Pro (falling
 *       down a hierarchy of other image models when it can't serve the request)
 *       removes wires/bushes/clutter AND repaints the painted surfaces into the
 *       reference palette, so the canvas the masks are aligned to already looks
 *       freshly painted. Enabled via REPLICATE_IMAGE_CLEANER_ENABLED.
 *
 *       <p><b>The masks depend on this step.</b> When the cleaner is enabled and
 *       every provider declines, the run FAILS here and the mask model is never
 *       called. That is deliberate: a mask generated against the raw photo is
 *       aligned to a canvas the studio does not display — the frontend renders the
 *       cleaned image — and, worse, the mask model reads clutter as architecture
 *       (a wire crossing a wall becomes a wall edge, an unplastered shell becomes
 *       cladding and blacks out). Half a pipeline produces regions in the wrong
 *       places, which looks to every check like a successful run; failing honestly
 *       lets the user say so through the report channel instead.</li>
 *   <li>One image-edit call ({@link ReplicateMaskSegmenter}, Nano Banana
 *       Pro) edits the cleaned photo into a flat colour-blocked image: RED = main
 *       paintable wall, GREEN = accent / highlighter wall, BLUE = trim &
 *       frames, BLACK = everything else (sky, ground, stone, windows, fixtures,
 *       plus the door panels and metal railings — kept as fixed features:
 *       dark-brown doors, charcoal-grey railings — so they are deliberately
 *       excluded from the recolourable
 *       masks). Because it paints onto the real surfaces rather than drawing an
 *       abstract map, the colour blocks stay aligned to the canvas.</li>
 *   <li>{@link MaskProcessor#splitColorCodedMask} splits the colored mask
 *       into per-category binary masks server-side.</li>
 *   <li>Each non-empty category is smooth-upscaled to the canvas resolution
 *       (see {@link #resizeMaskToCanvas}), uploaded to S3 and persisted as a
 *       {@link Region} row exactly as the model produced it — the masks get
 *       no further post-processing, so what the model drew is what's
 *       stored.</li>
 * </ol>
 *
 * <h3>When the walls don't come out (but the clean did)</h3>
 * The two halves fail differently, and only one of them is fatal.
 *
 * <p>A failed CLEAN ends the run, for the reason above: there is no correct canvas
 * to generate masks from. A failed MASK does NOT. The expensive half already
 * succeeded — the photo is cleaned, repainted and paid for — and the studio has a
 * perfectly good tool for drawing walls by hand, free on every plan. So the project
 * finishes SEGMENTED on its cleaned canvas with zero auto regions (exactly the shape
 * a MANUAL-mode run has), carrying {@code autoMaskFailed} so the studio can say what
 * happened and point at "Add a wall". Failing it instead threw away work that was
 * done and left the user at a dead end a minute of clicking would have cleared.
 *
 * <p>The cost of that kindness is that nobody finds out: a user with a working room
 * does not file a complaint, so the mask model's bad day would never reach anyone.
 * The pipeline therefore files the report itself, against the project's owner, via
 * {@link com.gridstore.huevista.maskreport.service.MaskReportService#reportAutoMaskFailure}
 * — the one failure in this system the backend can see for itself.
 *
 * <h3>Manual flow (segmentPointAndSave)</h3>
 * The user clicks a point on the photo in the frontend. We call SAM 2 on
 * Replicate with that single point as a positive prompt; SAM returns the
 * mask of the surface at the click. Saved as a MANUAL Region. This is
 * the safety-net for cases the auto path misses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentationService {

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final StorageService storageService;
    private final RestTemplate restTemplate;
    private final ReplicateMaskSegmenter maskSegmenter;
    private final ImageCleanerService imageCleaner;
    private final StubAiPipeline stubAiPipeline;
    private final AiFailureSimulator failureSimulator;
    private final ImageRepository imageRepository;
    private final com.gridstore.huevista.image.service.ClaudeVisionService claudeVision;
    private final com.gridstore.huevista.billing.service.BillingService billingService;
    private final com.gridstore.huevista.account.repository.CustomerAccessCodeRepository accessCodeRepository;
    private final ProjectBillingResolver billingResolver;
    private final com.gridstore.huevista.maskreport.service.MaskReportService maskReportService;

    /** Optional, mirrors ProjectService: present when the Redis-backed queue is in play. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.gridstore.huevista.project.queue.SegmentationJobQueue segmentationJobQueue;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    /**
     * SAM 2 model version on Replicate (for click-to-segment). When blank,
     * we call the official endpoint /models/meta/sam-2/predictions which
     * always uses the latest published version.
     */
    @Value("${replicate.sam2.model-version:}")
    private String sam2ModelVersion;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_ATTEMPTS = 30; // 60s worst case per request

    /**
     * Click-to-segment is synchronous: the request thread blocks while SAM 2 runs
     * (up to ~60s). Without a cap, a burst of clicks can hold every Tomcat worker
     * hostage and wedge the whole API. Beyond this many concurrent segmentations
     * we fail fast with 503 instead of queueing more blocked threads.
     */
    private static final int MAX_CONCURRENT_POINT_SEGMENTATIONS = 8;
    private final java.util.concurrent.Semaphore pointSegmentationSlots =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_POINT_SEGMENTATIONS);

    // ========================================================================
    // AUTO PATH
    // ========================================================================

    @Async("aiTaskExecutor")
    public void segmentAsync(String projectId, String imageUrl) {
        try {
            log.info("Starting wall segmentation: project={}", projectId);

            // Stub mode calls neither Replicate model, so it must not demand a
            // token — the whole point is to run this flow without one.
            if (!stubAiPipeline.isEnabled()
                    && (replicateApiToken == null || replicateApiToken.isBlank())) {
                markFailed(projectId, "REPLICATE_API_TOKEN not configured");
                return;
            }

            // Storage scope: a normal project is owned by a user; a guest project
            // (no user) is owned by its access code. Either way this string is only
            // used as the storage prefix for the cleaned image and mask uploads.
            // A non-null accessCodeId here also marks this as a GUEST run, whose AI
            // cost is billed to the issuing shop — but only once it succeeds (below).
            String ownerUserId = projectRepository.findUserIdById(projectId).orElse(null);
            String projectAccessCodeId = projectRepository.findAccessCodeIdById(projectId).orElse(null);
            String userId = ownerUserId != null ? ownerUserId : projectAccessCodeId;
            if (userId == null) {
                markFailed(projectId, "Project owner not found");
                return;
            }
            // Who PAYS is a separate question from who OWNS: a redeemed customer's project
            // is owned by the customer but billed to the shop that issued their code (and
            // spends the credit that code is holding). Resolved once here so every charge
            // site below agrees. See ProjectBillingResolver.
            ProjectBillingResolver.Target billing = billingResolver.resolve(projectId).orElse(null);

            // Wipe stale auto regions; MANUAL click-segments are preserved.
            regionRepository.deleteAutoRegionsByProjectId(projectId);

            // Load + cache dimensions on the upload entity so click-segment
            // later doesn't have to.
            UploadedImage uploadedImage = loadAndEnsureDimensions(projectId);

            // Step 1: Image cleaner (optional, opt-in). Sends the photo to
            // Nano Banana Pro asking for clutter removed AND painted surfaces
            // repainted into the reference palette. When it succeeds the
            // cleaned image becomes the canvas the masks are aligned to;
            // otherwise we mask the original directly. The cleaned bytes are
            // also kept in memory: the stored masks are sized off this canvas
            // (see tryColorCodedSegmentation). An ADMIN can skip this step per
            // run via the segment request's cleanImage=false testing knob
            // (persisted on the project — see requestSegmentation).
            // The stub testing mode (huevista.testing.stub-ai.enabled) forces the
            // same skip globally: the photo stays exactly as uploaded, and no
            // Replicate clean call — nor the Claude cleaning-hint call nested
            // inside it — is made. See StubAiPipeline.
            boolean stubbed = stubAiPipeline.isEnabled();
            boolean skipClean = stubbed || Boolean.TRUE.equals(
                    projectRepository.findSkipImageCleanById(projectId).orElse(null));
            if (skipClean) {
                log.info("Image cleaner skipped for project {} ({})", projectId,
                        stubbed ? "stub AI pipeline enabled" : "admin cleanImage=false");
                // Drop any cleaned canvas left by a previous run: this run's
                // masks align to the ORIGINAL photo, and a stale cleaned key
                // would make the frontend render them on the wrong canvas.
                persistCleanedImageKey(projectId, null);
            }
            // The scene (INDOOR / OUTDOOR) picks the cleaning prompt, the mask prompt's
            // accent rule, the sky filter and the opening palette, so an unresolved one
            // is not a cosmetic detail — see resolveScene.
            ImageType scene = resolveScene(uploadedImage);

            // How THIS run's cleaning prompt should differ from the stock one, resolved
            // once here so the cleaner and the hint call are prompted from the same
            // answer. This is where the photo is looked at properly, which every run
            // does; the furnishing and camera clauses only appear when someone ticked
            // the box asking for them.
            ImageCleanerService.PromptOptions promptOptions =
                    resolvePromptOptions(uploadedImage, projectId, scene);

            // TESTING ONLY: this run may have been asked to pretend one half of the
            // pipeline declined, so the recovery paths can be walked through on demand
            // rather than waited for. Resolved once, here, so both halves below read the
            // same answer and neither pays for a model call it is about to discard.
            String simulate = projectRepository.findSimulatedFailureById(projectId).orElse(null);
            boolean simulateCleanFailure =
                    failureSimulator.simulates(AiFailureSimulator.Stage.CLEAN, simulate);
            boolean simulateMaskFailure =
                    failureSimulator.simulates(AiFailureSimulator.Stage.MASK, simulate);
            if (simulateCleanFailure || simulateMaskFailure) {
                log.warn("Project {} is running with SIMULATED AI failures (clean={}, mask={}) — " +
                        "the models will not be called for those stages", projectId,
                        simulateCleanFailure, simulateMaskFailure);
            }

            String maskImageUrl = imageUrl;
            byte[] cleanedBytes = null;
            // Whether a cleaned canvas is REQUIRED before masks may be generated: the
            // cleaner is on and this run isn't one of the deliberate skips. A SIMULATED
            // clean failure counts as required too — otherwise the rehearsal only works
            // on a box where the cleaner happens to be configured, which is the opposite
            // of what a testing knob is for.
            boolean cleanRequired = !skipClean
                    && (imageCleaner.isAvailable() || simulateCleanFailure);
            // ADMIN testing knob: run this ONE clean on a named model instead of the
            // configured one, so two models can be compared on the same photo. Null —
            // the normal case — leaves the configured model in charge. Validated against
            // the catalogue when the request was taken, so it is safe to pass on.
            String cleanModel = projectRepository.findCleanModelById(projectId).orElse(null);
            try {
                Optional<byte[]> cleanedOpt = (skipClean || simulateCleanFailure)
                        ? Optional.empty()
                        // The listener is what turns the cleaner's model chain into
                        // something the waiting studio can see: each link says which
                        // model it is asking and that the last one was busy, and the
                        // status endpoint hands the sentence straight back.
                        : imageCleaner.cleanImage(imageUrl, scene, cleanModel,
                                note -> say(projectId, note), promptOptions);
                if (cleanedOpt.isPresent()) {
                    cleanedBytes = cleanedOpt.get();
                    String cleanedKey = storageService.store(
                            cleanedBytes, userId, "cleaned.jpg", "image/jpeg");
                    persistCleanedImageKey(projectId, cleanedKey, cleanedBytes);
                    maskImageUrl = storageService.getPublicUrl(cleanedKey);
                    log.info("ImageCleaner produced cleaned image for project {}, storageKey={}",
                            projectId, cleanedKey);
                }
            } catch (Exception e) {
                log.warn("ImageCleaner step failed for project {}: {}", projectId, e.getMessage());
            }

            // MANUAL mask mode: the pipeline deliberately stops after the
            // compulsory clean-up — the user marks walls themselves with
            // click-to-segment / hand-drawing (free, unlimited on every tier).
            // The project is usable right away (cleaned canvas when the clean
            // succeeded, original photo otherwise), and only the IMAGE credit
            // is charged: no AI wall detection ran, so no auto-mask credit.
            //
            // A failed clean does NOT fail a manual run: nothing is generated in this
            // mode, so there is no mask to misalign — the user marks walls on whatever
            // canvas exists, and the original photo is a perfectly good one to draw on.
            // The AUTO path below is the one that depends on the clean.
            if ("MANUAL".equalsIgnoreCase(
                    projectRepository.findMaskModeById(projectId).orElse(null))) {
                markSegmented(projectId);
                billRun(billing, false);
                log.info("Manual mask mode: stopped after clean-up for project {} " +
                        "(image credit charged, walls to be marked by hand, cleaned canvas={})",
                        projectId, cleanedBytes != null);
                return;
            }

            // The gate. Masks are generated FROM the cleaned canvas, so without one
            // there is nothing correct to generate them from — see the class doc.
            if (cleanRequired && cleanedBytes == null) {
                // A cleaned image from an earlier run would put this run's masks on a
                // canvas that no longer matches, so drop the reference along with it.
                persistCleanedImageKey(projectId, null);
                // The chain is four models across two independent families, so all of them
                // declining within a few minutes is a statement about capacity rather than
                // about this photo — a safety refusal would have stopped the chain on the
                // first model instead of reaching the last. So the user is told the system
                // is loaded and to come back, not to change their picture.
                markFailed(projectId, FailureStage.CLEAN, ImageCleanerService.SYSTEM_UNDER_LOAD);
                return;
            }

            // Without a cleaned canvas (cleaner disabled or failed) the
            // ORIGINAL photo is the canvas the frontend renders on — load it
            // so the stored masks can be sized off its exact aspect and
            // resolution (see tryColorCodedSegmentation).
            byte[] snapFallbackBytes = null;
            if (cleanedBytes == null) {
                try {
                    snapFallbackBytes = storageService.load(uploadedImage.getStorageKey());
                } catch (Exception e) {
                    log.warn("Could not load original photo as sizing canvas for project {}: {}",
                            projectId, e.getMessage());
                }
            }

            // Step 2: color-coded mask via Replicate (Nano Banana Pro), run against the
            // CLEANED canvas whenever there is one. Scene drives the accent-wall rule:
            // interiors always get one accent wall to highlight.
            if (!simulateMaskFailure && tryColorCodedSegmentation(projectId, userId, maskImageUrl,
                    scene, cleanedBytes, snapFallbackBytes,
                    uploadedImage.getWidth(), uploadedImage.getHeight())) {
                markSegmented(projectId);
                // Charge one IMAGE credit (compulsory clean-up) plus one AUTO-MASK credit
                // (AI wall detection ran) now that walls were actually produced — a failed
                // run never costs a credit.
                billRun(billing, true);
                log.info("Segmentation complete: project={}", projectId);
                return;
            }

            // The clean landed but the walls didn't — and that is NOT a dead end. The
            // canvas the user came for exists, so hand it over with the walls left to
            // mark by hand, and tell the team ourselves. See the class doc.
            //
            // Except when the mask model was never asked at all, because it isn't
            // switched on in this deployment. Same outcome for the user — a cleaned
            // canvas to mark by hand — but nothing to report: a report says "look at
            // what this model did with this photo", and no model looked at it. Filing
            // one per project would fill the queue with a single line of configuration.
            // (Mirrors the guard in tryColorCodedSegmentation; a SIMULATED failure is
            // deliberately still reported, since watching the report arrive is the
            // whole point of rehearsing one.)
            boolean maskModelWasAsked = simulateMaskFailure
                    || stubAiPipeline.isEnabled() || maskSegmenter.isConfigured();
            handOverForManualWalls(projectId, cleanedBytes != null, maskModelWasAsked);
            billRun(billing, false);

        } catch (Exception e) {
            // Still a real FAILED, unlike the empty-mask case above. That one is a model
            // declining to find walls, with everything else intact; this is the run
            // falling over somewhere unknown — storage, the database, a decode — and we
            // have no idea whether the canvas the studio would open is even there.
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, FailureStage.MASK, "Segmentation failed: " + e.getMessage());
        } finally {
            // The run reached a terminal outcome (SEGMENTED or FAILED), so the queue
            // entry must not be retried. No-op when the job didn't come from the queue.
            if (segmentationJobQueue != null) {
                try {
                    segmentationJobQueue.acknowledge(projectId, imageUrl);
                } catch (Exception ackError) {
                    log.warn("Could not acknowledge segmentation job for project {}: {}",
                            projectId, ackError.getMessage());
                }
            }
        }
    }

    /**
     * One mask-model call ({@link ReplicateMaskSegmenter}) returns a single
     * color-coded image; we split it into per-category binary masks,
     * smooth-upscale each one to the canvas resolution (see
     * {@link #resizeMaskToCanvas}) and persist each non-empty one as a Region
     * row, otherwise exactly as the model produced it.
     *
     * <p>Generative segmentation occasionally produces a dud (no red main
     * wall at all, an off-palette image the split can't use). One dud used
     * to fail the whole project; now the model round-trip is retried up to
     * {@code huevista.segmentation.auto-mask-attempts} times (fresh
     * generation each time — the models are non-deterministic, so a second
     * roll usually lands). Nothing is persisted until an attempt yields a
     * usable MAIN wall, so a failed attempt can never leave orphan
     * accent/trim rows behind on a FAILED project.
     *
     * Pixel size thresholds (5000 px for walls, 2000 px for trim) filter
     * categories the model barely produced — usually a sign the model
     * couldn't find that surface in the photo (e.g. no distinct accent
     * wall). We skip saving them rather than persisting a tiny noise mask.
     */
    boolean tryColorCodedSegmentation(String projectId, String userId,
                                      String imageUrl, ImageType scene,
                                      byte[] cleanedBytes, byte[] originalBytes,
                                      int imageWidth, int imageHeight) {
        try {
            // Stub mode draws the colour-coded mask locally, so the real
            // segmenter's configuration is irrelevant to it.
            if (!stubAiPipeline.isEnabled() && !maskSegmenter.isConfigured()) {
                log.warn("Mask segmenter not configured — set " +
                        "REPLICATE_NANO_BANANA_ENABLED=true");
                return false;
            }

            // The canvas SIZES the stored masks: the cleaned repaint when
            // present (it's what the frontend renders on), otherwise the
            // original photo.
            BufferedImage sizeCanvas = decodeCanvasForMasks(
                    cleanedBytes != null ? cleanedBytes : originalBytes);

            // Masks are stored at the CANVAS's aspect and resolution (capped at
            // MAX_MASK_DIM), not at whatever size the model generated — the
            // frontend stretches each mask over the canvas, so any aspect drift
            // here shears every region off its surface.
            int targetW, targetH;
            if (sizeCanvas != null) {
                targetW = sizeCanvas.getWidth();
                targetH = sizeCanvas.getHeight();
            } else {
                double scale = Math.min(1.0,
                        (double) MAX_MASK_DIM / Math.max(imageWidth, imageHeight));
                targetW = Math.max(1, (int) Math.round(imageWidth * scale));
                targetH = Math.max(1, (int) Math.round(imageHeight * scale));
            }

            int attempts = Math.max(1, autoMaskAttempts);
            // An ADMIN pinning this run to one model is asking about THAT model, so the
            // chain collapses to it — the same reasoning as the cleaner's override. A
            // usable mask produced by a sibling tier would answer a question nobody asked
            // and be indistinguishable, afterwards, from one the pinned model made.
            // Stub mode never talks to Replicate, so there is no chain to walk — one
            // pseudo-model, still retried, so the retry path itself stays exercised.
            // Decided BEFORE the chain is resolved, not after: asking the segmenter for
            // its models is itself a call into the thing stub mode exists to avoid.
            List<String> chain;
            if (stubAiPipeline.isEnabled()) {
                chain = List.of("stub");
            } else {
                String override = projectRepository.findMaskModelById(projectId).orElse(null);
                chain = (override != null && !override.isBlank())
                        ? List.of(override.trim())
                        : maskSegmenter.modelChain();
            }
            // A chain that came back empty must not silently mean "make no attempt". One
            // null entry is the pre-chain behaviour exactly: ask whatever the segmenter's
            // own configuration says. Reachable through misconfiguration
            // (nano-banana.model blank) and worth surviving either way.
            if (chain.isEmpty()) chain = java.util.Collections.singletonList(null);

            ProcessedMasks masks = null;
            int budget = attempts * Math.max(1, chain.size());
            int spent = 0;
            outer:
            for (String candidate : chain) {
                for (int attempt = 1; attempt <= attempts; attempt++) {
                    spent++;
                    if (spent > 1) {
                        log.info("Auto-mask retry {}/{} for project {} on {} — the previous "
                                + "generation produced no usable main wall",
                                spent, budget, projectId, candidate);
                    }
                    say(projectId, maskNote(candidate, spent, budget));
                    masks = generateAndProcessMasks(projectId, imageUrl, scene,
                            targetW, targetH, candidate);
                    if (masks != null) break outer;
                }
            }
            if (masks == null) {
                log.info("Mask model didn't produce a usable main wall for project {} " +
                        "after {} attempt(s) across {}", projectId, spent, chain);
                return false;
            }

            // Keep the accepted generation's raw colour-coded image for the
            // admin mask viewer. Best-effort: a storage failure must never
            // fail the segmentation itself.
            try {
                String rawKey = storageService.store(
                        masks.raw(), userId, "raw_mask.png", "image/png");
                persistRawMaskKey(projectId, rawKey);
            } catch (Exception e) {
                log.warn("Could not persist raw colour-coded mask for project {}: {}",
                        projectId, e.getMessage());
            }

            int saved = 0;
            int displayOrder = 0;
            // Labels come from RegionCategory itself, not from string literals here.
            // Three places named the same three surfaces and no two agreed: the studio
            // showed "Main wall / Accent wall / Border" before a photo, this wrote
            // "Main Wall / Accent Wall / Trim & Frames" after detection, and
            // ProjectService.defaultLabel had a third spelling again. The names a
            // customer is choosing between changed halfway through the flow.
            saveCategoryRegion(projectId, userId, masks.main(),
                    RegionCategory.MAIN_WALL.getDefaultLabel(), RegionCategory.MAIN_WALL, displayOrder++,
                    defaultHexFor(RegionCategory.MAIN_WALL, scene));
            saved++;
            if (masks.accent() != null) {
                saveCategoryRegion(projectId, userId, masks.accent(),
                        RegionCategory.ACCENT_WALL.getDefaultLabel(), RegionCategory.ACCENT_WALL, displayOrder++,
                        defaultHexFor(RegionCategory.ACCENT_WALL, scene));
                saved++;
            }
            if (masks.trim() != null) {
                saveCategoryRegion(projectId, userId, masks.trim(),
                        RegionCategory.TRIM.getDefaultLabel(), RegionCategory.TRIM, displayOrder++,
                        defaultHexFor(RegionCategory.TRIM, scene));
                saved++;
            }
            log.info("Mask segmenter saved {} regions for project {}", saved, projectId);
            return true;
        } catch (Exception e) {
            log.warn("Mask segmentation path failed for project {}: {}", projectId, e.getMessage(), e);
            return false;
        }
    }

    /** Per-category masks of one usable generation: main is always present;
     *  accent/trim are null when the model produced none (or only noise) for
     *  that category. {@code raw} is the model's original colour-coded image
     *  the categories were split from — persisted for the admin mask
     *  viewer. */
    private record ProcessedMasks(byte[] main, byte[] accent, byte[] trim, byte[] raw) {}

    /**
     * One model round-trip: generate the colour-coded image, split it and
     * resize every category to the canvas resolution — nothing else touches
     * the masks. Returns null when the round produced no usable MAIN wall
     * (empty output, off-palette image, main below the noise threshold) —
     * nothing has been persisted at that point, so the caller is free to
     * retry with a fresh generation.
     */
    private ProcessedMasks generateAndProcessMasks(String projectId, String imageUrl,
                                                   ImageType scene,
                                                   int targetW, int targetH,
                                                   String modelId) {
        // Testing stub: draw the colour-coded image locally (vertical
        // RED|GREEN|BLUE thirds) instead of paying for a generation. Produced
        // at the canvas size, so the resize below is a no-op.
        Optional<byte[]> colorRaw;
        if (stubAiPipeline.isEnabled()) {
            try {
                colorRaw = Optional.of(stubAiPipeline.colorCodedMask(targetW, targetH));
            } catch (Exception e) {
                log.warn("Stub colour-coded mask generation failed for project {}: {}",
                        projectId, e.getMessage());
                return null;
            }
        } else {
            // The model this round is on, chosen by the chain in the caller. Passed as an
            // "override" because that is the parameter the segmenter already has for
            // "ask this exact model" — the chain and an admin's pin want the same thing
            // of it, and a second parameter meaning the same would be one too many.
            colorRaw = maskSegmenter.generateColorCodedMask(imageUrl, scene, modelId);
        }
        if (colorRaw.isEmpty()) {
            log.info("Mask segmenter returned no color-coded mask for project {}", projectId);
            return null;
        }

        Map<String, byte[]> parts;
        try {
            // Sky filter applies to exterior/unknown scenes only: indoors there
            // is no sky, and a full-bleed wall may legitimately touch the top.
            parts = MaskProcessor.splitColorCodedMask(colorRaw.get(), 2000,
                    scene != ImageType.INDOOR);
        } catch (Exception e) {
            log.warn("splitColorCodedMask failed for project {}: {}", projectId, e.getMessage());
            return null;
        }
        log.info("Mask split [project={}]: {}", projectId, parts.keySet());
        logAspectDriftIfAny(colorRaw.get(), targetW, targetH, projectId);

        byte[] mainBytes = resizeMaskToCanvas(parts.get("main"), targetW, targetH);
        if (mainBytes == null || safeForegroundCount(mainBytes) < 5000) {
            return null;
        }
        byte[] accentBytes = resizeMaskToCanvas(parts.get("accent"), targetW, targetH);
        if (accentBytes != null && safeForegroundCount(accentBytes) < 5000) {
            accentBytes = null;
        }
        byte[] trimBytes = resizeMaskToCanvas(parts.get("trim"), targetW, targetH);
        if (trimBytes != null && safeForegroundCount(trimBytes) < 2000) {
            trimBytes = null;
        }
        return new ProcessedMasks(mainBytes, accentBytes, trimBytes, colorRaw.get());
    }

    private void saveCategoryRegion(String projectId, String userId, byte[] maskBytes,
                                    String label, RegionCategory category, int displayOrder,
                                    String appliedHex)
            throws java.io.IOException {
        String key = storageService.store(
                maskBytes, userId, category.name().toLowerCase() + ".png", "image/png");
        // Persist the S3 KEY, not a presigned URL. Presigned URLs expire (default 60 min)
        // so storing one freezes a dead link into the DB. The read path presigns the key
        // fresh on every response (see ProjectService#resolveMaskUrl), exactly like the
        // original image URL is built.
        //
        // appliedHexCode is pre-filled with the scene's default reference colour so the
        // project opens already painted ("colour on create"); the user can still recolour
        // any region afterwards. Frontend treats a non-null appliedHexCode as "painted".
        regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label(label)
                .category(category)
                .maskUrl(key)
                .maskData(key)
                .displayOrder(displayOrder)
                .appliedHexCode(appliedHex)
                .build());
        log.info("Saved {} region for project {}: {} (default {})",
                category, projectId, key, appliedHex);
    }

    // Exterior "colour on create" reference palette. These are the swatches the
    // project opens painted with (through the masks, on the cleaned white
    // canvas) — the canvas itself stays white so the frontend's scene-light
    // anchored shading can treat the cleaned photo as an illumination map.
    // MUST stay in sync with the frontend's DEFAULT_HEX_FOR_KIND
    // (visualizer.tsx).
    private static final String EXT_MAIN_HEX = "#e8d5b0";   // Cashmere Beige (0342)
    private static final String EXT_ACCENT_HEX = "#b0603e"; // Burnt Sienna (6118)
    private static final String EXT_TRIM_HEX = "#4a362a";   // Dark Clove (8511)

    /**
     * Default "colour on create" reference shade for an auto-detected category.
     * Exteriors open in the reference combo — beige body, sienna feature wall,
     * dark-clove trim — so the first render already reads like a designed
     * colour scheme instead of a flat all-white house. Interiors still open
     * white (a neutral base the user colours themselves). Doors and railings
     * are not a recolourable category (the clean step keeps them as fixed
     * features). Returns null for MANUAL.
     */
    private static String defaultHexFor(RegionCategory category, ImageType scene) {
        if (scene != ImageType.INDOOR) {
            return switch (category) {
                case MAIN_WALL, OTHER_WALL -> EXT_MAIN_HEX;
                case ACCENT_WALL -> EXT_ACCENT_HEX;
                case TRIM -> EXT_TRIM_HEX;
                case MANUAL -> null;
            };
        }
        return switch (category) {
            case MAIN_WALL, OTHER_WALL, ACCENT_WALL, TRIM -> "#ffffff";
            case MANUAL -> null;
        };
    }

    /** Longest side (px) for stored region masks. The cleaned canvas is
     *  upscaled to ~3840px, but a 2048px mask scaled up by the renderer's
     *  bilinear sampling is visually indistinguishable at that size and keeps
     *  the PNGs fast. */
    private static final int MAX_MASK_DIM = 2048;

    /**
     * How many colour-coded generations to try PER MODEL before moving to the next one
     * in {@link ReplicateMaskSegmenter#modelChain()}.
     *
     * <p>Two, and unlike the cleaner's single attempt that is the right number here.
     * The cleaner moves on immediately because its failures are queue failures, and a
     * busy pool stays busy. A mask usually fails a different way: the model answers, and
     * the answer is a dud — no red main wall, an off-palette image the split cannot use.
     * That is non-determinism rather than capacity, and a second roll of the SAME model
     * lands often enough to be the cheapest thing to try. Only once a model has produced
     * two duds is it worth believing the model itself is wrong for this photo, which is
     * when the sibling tier gets its turn.
     *
     * <p>So the full budget is 2 × the chain length — with the defaults, Nano Banana Pro
     * twice, then Nano Banana 2 twice.
     */
    @Value("${huevista.segmentation.auto-mask-attempts:2}")
    private int autoMaskAttempts;

    /**
     * Smooth-upscales a raw split mask to the canvas aspect/resolution — the
     * model outputs ~1K and nearest scaling to a 4K canvas shows staircase
     * blocks. A pure resize: the mask boundaries stay exactly where the model
     * drew them. Best-effort — a failure keeps the model-resolution bytes.
     */
    private byte[] resizeMaskToCanvas(byte[] mask, int w, int h) {
        if (mask == null) return null;
        try {
            return MaskProcessor.resizeBinarySmooth(mask, w, h);
        } catch (Exception e) {
            log.warn("Mask smooth-upscale to {}x{} failed, keeping model resolution: {}",
                    w, h, e.getMessage());
            return mask;
        }
    }

    /** Decodes a canvas image (cleaned or original) and downsamples it to the
     *  stored-mask resolution. Null input or a decode failure returns null —
     *  the caller falls back to sizing the masks off the upload's cached
     *  dimensions. */
    private BufferedImage decodeCanvasForMasks(byte[] cleanedBytes) {
        if (cleanedBytes == null) return null;
        try {
            return MaskProcessor.downsample(MaskProcessor.decode(cleanedBytes), MAX_MASK_DIM);
        } catch (Exception e) {
            log.warn("Could not decode canvas for mask sizing: {}", e.getMessage());
            return null;
        }
    }

    /** Logs (never fails) when the colour-coded mask's aspect drifts >5% from
     *  the canvas — the tell-tale of an aspect-bucketed model output, which
     *  shears every region off its real surface once stretched. */
    private void logAspectDriftIfAny(byte[] colorMask, int targetW, int targetH, String projectId) {
        try {
            BufferedImage m = MaskProcessor.decode(colorMask);
            double maskAr = (double) m.getWidth() / m.getHeight();
            double canvasAr = (double) targetW / targetH;
            if (Math.abs(maskAr / canvasAr - 1.0) > 0.05) {
                log.warn("Color-coded mask {}x{} has a different aspect than canvas {}x{} " +
                                "for project {} — regions may sit off their surfaces; check the " +
                                "replicate.nano-banana.aspect-ratio input",
                        m.getWidth(), m.getHeight(), targetW, targetH, projectId);
            }
        } catch (Exception ignored) {
            // best-effort diagnostics only
        }
    }

    /** Counts foreground pixels, returning 0 if the mask can't be decoded. */
    private int safeForegroundCount(byte[] mask) {
        try {
            return MaskProcessor.countForeground(mask);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * A completed run costs nothing further.
     *
     * The project's credit was taken when the project was CREATED, so by the time the AI
     * finishes, the work is already paid for. Charging here as well as there would bill a
     * room twice, and charging ONLY here is what made "15 projects a month" invisible: a
     * shop could create any number and only met the ceiling once a photo was uploaded and
     * a customer was watching. Kept as a named no-op so the call site still reads as the
     * billing boundary it is, and so the log line that says who a run belonged to survives.
     */
    private void billRun(ProjectBillingResolver.Target billing, boolean autoMaskRan) {
        if (billing == null) {
            log.warn("Segmentation succeeded but no billable account was resolved");
            return;
        }
        log.info("Run completed for {}: autoMask={} (code={}) — already paid for at creation",
                billing.billedUserId(), autoMaskRan, billing.accessCodeId());
    }

    // ========================================================================
    // MANUAL PATH (click-to-segment)
    // ========================================================================

    /**
     * Synchronously segments a single user-clicked point with SAM 2 and
     * persists the resulting mask as a MANUAL region. Coordinates are
     * normalized 0–1 in the frontend; we scale by the image's real pixel
     * dimensions (cached on UploadedImage) before sending to SAM 2.
     */
    public Region segmentPointAndSave(String projectId, String imageUrl,
                                      int imageWidth, int imageHeight,
                                      double x, double y, String label)
            throws InterruptedException {
        if (!pointSegmentationSlots.tryAcquire()) {
            throw new java.util.concurrent.RejectedExecutionException(
                    "Too many segmentations are running right now. Please try again in a moment.");
        }
        try {
            return doSegmentPointAndSave(projectId, imageUrl, imageWidth, imageHeight, x, y, label);
        } finally {
            pointSegmentationSlots.release();
        }
    }

    private Region doSegmentPointAndSave(String projectId, String imageUrl,
                                         int imageWidth, int imageHeight,
                                         double x, double y, String label)
            throws InterruptedException {
        log.info("Point segmentation: project={} x={} y={} size={}x{} label={}",
                projectId, x, y, imageWidth, imageHeight, label);

        double pixelX = x * imageWidth;
        double pixelY = y * imageHeight;
        List<List<Double>> inputPoints = List.of(List.of(pixelX, pixelY));
        List<Integer> inputLabels = List.of(1);

        Map<String, Object> input = Map.of(
                "image", imageUrl,
                "input_points", inputPoints,
                "input_labels", inputLabels
        );

        Map<String, Object> created = startSam2Prediction(input);
        if (created == null) {
            throw new ExternalServiceException("Failed to create Replicate prediction for point segmentation");
        }
        // Prefer: wait usually returns the finished prediction in the create
        // response itself — only fall back to polling when it didn't finish
        // within the wait window.
        Map<String, Object> result = "succeeded".equals(created.get("status"))
                ? created
                : pollUntilDone((String) created.get("id"));
        if (result == null) {
            throw new ExternalServiceException("Point segmentation timed out or failed");
        }
        String maskUrl = extractFirstMaskUrl(result.get("output"));
        if (maskUrl == null) {
            throw new ExternalServiceException("No mask URL in SAM 2 point segmentation output");
        }

        // SAM's raw output used to be persisted as-is: a Replicate delivery URL
        // (which expires) pointing at a mask that is sometimes INVERTED. Fix
        // the inversion (a format repair, not an enhancement — the boundary is
        // untouched) and store the bytes in OUR storage so the reference stays
        // live. Best-effort: any failure falls back to the raw URL, which is
        // exactly the old behaviour.
        String maskRef = maskUrl;
        try {
            maskRef = persistRawPointMask(projectId, maskUrl);
        } catch (Exception e) {
            log.warn("Point mask persistence failed for project {}, storing the raw SAM URL: {}",
                    projectId, e.getMessage());
        }

        int displayOrder = regionRepository.countByProjectId(projectId);
        String resolvedLabel = (label != null && !label.isBlank())
                ? label
                : "Region " + (displayOrder + 1);

        Region region = Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label(resolvedLabel)
                .category(RegionCategory.MANUAL)
                .maskUrl(maskRef)
                .maskData(maskRef)
                .displayOrder(displayOrder)
                .build();

        return regionRepository.save(region);
    }

    /**
     * Downloads a SAM point mask, repairs the occasional inverted output
     * (white-foreground fix — the mask itself is stored exactly as SAM drew
     * it), stores the PNG under the project owner's scope and returns the
     * storage KEY (presigned fresh on every read, like auto masks — a stored
     * Replicate URL dies within the hour).
     */
    private String persistRawPointMask(String projectId, String samMaskUrl)
            throws java.io.IOException {
        byte[] raw = restTemplate.getForObject(samMaskUrl, byte[].class);
        if (raw == null || raw.length == 0) {
            throw new java.io.IOException("Empty SAM mask download");
        }
        byte[] out = MaskProcessor.ensureWhiteForeground(raw);
        String ownerUserId = projectRepository.findUserIdById(projectId).orElse(null);
        String storageScope = ownerUserId != null ? ownerUserId
                : projectRepository.findAccessCodeIdById(projectId).orElse(null);
        if (storageScope == null) {
            throw new java.io.IOException("Project owner not found for mask storage");
        }
        return storageService.store(out, storageScope, "manual.png", "image/png");
    }

    // ========================================================================
    // SHARED HELPERS
    // ========================================================================

    /**
     * Creates the SAM 2 prediction and returns the full response body (never
     * just the id): with the {@code Prefer: wait} header Replicate holds the
     * request open until the prediction finishes (up to the wait window), so
     * for a fast model like SAM 2 the create response usually already carries
     * status "succeeded" + output. That removes the poll loop's mandatory 2s
     * first sleep from every click-to-segment — the user sees their wall about
     * two seconds sooner — and frees the capped request thread earlier. If the
     * window elapses first, Replicate returns the in-progress prediction and
     * the caller falls back to polling exactly as before.
     */
    private Map<String, Object> startSam2Prediction(Map<String, Object> input) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);
        // 30s wait: comfortably above SAM 2's typical 1-5s runtime, safely
        // below the shared RestTemplate's 120s read timeout.
        headers.set("Prefer", "wait=30");

        boolean hasPinnedVersion = sam2ModelVersion != null && !sam2ModelVersion.isBlank();
        Map<String, Object> body = hasPinnedVersion
                ? Map.of("version", sam2ModelVersion, "input", input)
                : Map.of("input", input);
        String endpoint = hasPinnedVersion
                ? REPLICATE_BASE + "/predictions"
                : REPLICATE_BASE + "/models/meta/sam-2/predictions";

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> created = response.getBody();
            if (created == null || created.get("id") == null) {
                log.error("SAM 2 prediction create returned no body/id");
                return null;
            }
            return created;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 401/403 = bad token (config problem, will never recover by retrying);
            // 429 = rate limited (transient). Log them distinctly so ops can tell.
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                log.error("Replicate rejected our API token ({}). Check REPLICATE_API_TOKEN.",
                        e.getStatusCode());
            } else if (e.getStatusCode().value() == 429) {
                log.warn("Replicate rate limit hit while starting SAM 2 prediction");
            } else {
                log.error("SAM 2 prediction request rejected: {} {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
            }
            return null;
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.error("Replicate server error starting SAM 2 prediction: {}", e.getStatusCode());
            return null;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("Replicate unreachable or timed out starting SAM 2 prediction: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to start SAM 2 prediction: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pollUntilDone(String predictionId) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + replicateApiToken);

        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);
            ResponseEntity<Map> response = restTemplate.exchange(
                    REPLICATE_BASE + "/predictions/" + predictionId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );
            Map<String, Object> body = response.getBody();
            String status = (String) body.get("status");
            if ("succeeded".equals(status)) return body;
            if ("failed".equals(status) || "canceled".equals(status)) {
                log.warn("Prediction terminal status: {}", status);
                return null;
            }
        }
        return null;
    }

    /** Extracts the first mask URL from a SAM 2 single-point output. */
    @SuppressWarnings("unchecked")
    private static String extractFirstMaskUrl(Object output) {
        if (output instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String url) return url;
        } else if (output instanceof Map<?, ?> map) {
            Object masks = map.get("individual_masks");
            if (masks instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof String url) return url;
            }
        } else if (output instanceof String url) {
            return url;
        }
        return null;
    }

    /**
     * Loads the project's UploadedImage and ensures width/height are
     * cached. Click-to-segment scales normalized clicks against these
     * dimensions, so they must be available when the manual flow runs.
     */
    private UploadedImage loadAndEnsureDimensions(String projectId) throws java.io.IOException {
        String imageId = projectRepository.findImageIdById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project has no image: " + projectId));
        UploadedImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + imageId));

        if (image.getWidth() == null || image.getHeight() == null) {
            byte[] bytes = storageService.load(image.getStorageKey());
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) {
                throw new java.io.IOException("Could not decode image bytes: " + image.getStorageKey());
            }
            image.setWidth(decoded.getWidth());
            image.setHeight(decoded.getHeight());
            image = imageRepository.save(image);
            log.info("Cached dimensions for image {}: {}x{}",
                    image.getId(), decoded.getWidth(), decoded.getHeight());
        }
        return image;
    }

    /**
     * The photo's scene — INDOOR or OUTDOOR — resolved for certain before the models
     * are prompted, classifying the stored photo now if the upload never got a verdict.
     *
     * <p>Everything downstream branches on this and NOTHING re-checked it, so an
     * unresolved scene quietly became "outdoor" at four separate decisions: the cleaning
     * prompt (interiors are told to finish ceilings and floors, exteriors to clear sky
     * and wires), the mask prompt's accent rule (indoors always highlights one wall),
     * the sky filter (which discards a top-touching pale region — indoors that is the
     * wall meeting the ceiling, and it took the main wall with it), and the opening
     * palette (exteriors open in the beige/sienna combo, interiors white).
     *
     * <p>The upload path leaves it UNKNOWN in two real cases: <b>every guest upload</b>
     * — the kiosk skips classification because that endpoint is unauthenticated and a
     * per-upload AI call is an abuse vector — and any upload made while Claude was
     * unavailable. Both then ran the whole pipeline as an exterior, which is how an
     * interior room ends up processed as a facade. Here is the right place to fix that:
     * a run is already committed to spending on image models, so one classification call
     * is not the cost that matters, and the answer is written back to the image so a
     * re-run doesn't pay for it twice.
     *
     * <p>Best-effort by design: if the classifier is down or the photo won't decode, the
     * run continues on UNKNOWN exactly as before rather than failing over a hint.
     */
    private ImageType resolveScene(UploadedImage image) {
        ImageType known = image.getImageType();
        if (known != null && known != ImageType.UNKNOWN) return known;

        try {
            byte[] bytes = storageService.load(image.getStorageKey());
            ImageType detected = claudeVision.classifyStored(bytes);
            if (detected == null || detected == ImageType.UNKNOWN) {
                // The classifier says "not a room or a building". It is not this
                // service's job to reject an upload that is already paid for and
                // sitting in a project, so the run goes ahead on the exterior
                // treatment — but the log says why, because a run that goes strange
                // from here usually starts with a photo that isn't a house.
                log.warn("Scene classification for image {} came back INVALID — running as {}",
                        image.getId(), ImageType.UNKNOWN);
                return ImageType.UNKNOWN;
            }
            image.setImageType(detected);
            imageRepository.save(image);
            log.info("Resolved scene for image {}: {} (was UNKNOWN)", image.getId(), detected);
            return detected;
        } catch (Exception e) {
            log.warn("Could not resolve the scene for image {} — continuing as UNKNOWN: {}",
                    image.getId(), e.getMessage());
            return ImageType.UNKNOWN;
        }
    }

    /**
     * How this run's cleaning prompt should differ from the stock one, assembled from
     * the choices on the project.
     *
     * <p>The house type is resolved in three steps, most explicit first: an admin's
     * override wins, then whatever a previous analysis of this photo already found, then
     * a fresh analysis. That order is what lets an admin run the same photo twice under
     * two different types to compare the clauses — an override that a re-analysis could
     * quietly overrule would make the comparison meaningless — and it is also why the
     * analysis is not paid for twice on the same photo.
     *
     * <p>Furnishing and camera still default to the stock prompt: those two change what
     * the cleaned photo SHOWS, so they happen only when someone ticked the box asking
     * for them.
     */
    private ImageCleanerService.PromptOptions resolvePromptOptions(UploadedImage image,
                                                                   String projectId,
                                                                   ImageType scene) {
        ProjectRepository.CleanOptionsView knobs =
                projectRepository.findCleanOptionsById(projectId).orElse(null);
        if (knobs == null) return ImageCleanerService.PromptOptions.DEFAULT;

        CleanFurnishing furnishing = CleanFurnishing.parse(knobs.getCleanFurnishing());
        CleanAngle angle = CleanAngle.parse(knobs.getCleanAngle());

        HouseType type = HouseType.parse(knobs.getHouseType());
        if (type == HouseType.UNKNOWN) {
            type = image.getHouseType() == null ? HouseType.UNKNOWN : image.getHouseType();
        }
        // Not "did this run ask for it" any more — an unset flag means yes. Looking at
        // the photo is what a run DOES: the studio stopped offering it as a choice once
        // it was worth spending on every photo, and a guest at a kiosk sends no options
        // at all, so anything that treated null as no would quietly hand the walk-in a
        // worse canvas than the shop's own project gets. Only an explicit false — which
        // nothing but a direct API call sets — still skips it.
        if (type == HouseType.UNKNOWN && !Boolean.FALSE.equals(knobs.getAnalysePhoto())) {
            type = analyseAndRemember(image, scene);
        }
        // The scene is the answer four downstream decisions already depend on, so a type
        // that contradicts it — an admin who picked BATHROOM for a facade, or a stale
        // answer from before the scene was re-resolved — loses rather than winning.
        if (!type.fits(scene)) {
            log.warn("House type {} does not fit scene {} for project {} — ignoring it",
                    type, scene, projectId);
            type = HouseType.UNKNOWN;
        }
        return new ImageCleanerService.PromptOptions(type, furnishing, angle);
    }

    /**
     * Spend one Claude Haiku call looking at this photo properly, and write what comes
     * back onto the image so a second run of it doesn't pay again.
     *
     * <p>Best-effort in exactly the way {@link #resolveScene} is: the analysis only ever
     * adds a sentence to a prompt and a swatch to a screen, so a classifier that is down
     * costs the run nothing at all. It returns UNKNOWN and the run carries on with the
     * stock prompt.
     *
     * <p>Note what it does NOT do: it never writes back the scene. {@code resolveScene}
     * has already settled that, possibly from the upload, and letting a second opinion
     * overwrite it here would mean the cleaning prompt and the mask prompt could be
     * chosen from different answers to the same question.
     */
    private HouseType analyseAndRemember(UploadedImage image, ImageType scene) {
        try {
            byte[] bytes = storageService.load(image.getStorageKey());
            SceneAnalysis analysis = claudeVision.analyseStored(bytes);
            image.setHouseType(analysis.houseType());
            image.setDetectedWallHex(analysis.wallHex());
            image.setDetectedWallColour(analysis.wallColourName());
            image.setDetectedTrimHex(analysis.trimHex());
            imageRepository.save(image);
            log.info("Analysed image {}: type={} wall={} ({}) — scene stays {}",
                    image.getId(), analysis.houseType(), analysis.wallHex(),
                    analysis.wallColourName(), scene);
            return analysis.houseType();
        } catch (Exception e) {
            log.warn("Could not analyse image {} — continuing with the stock prompt: {}",
                    image.getId(), e.getMessage());
            return HouseType.UNKNOWN;
        }
    }

    /** Forgets the cleaned canvas — key and size together, so nothing downstream can
     *  read a size that belongs to an image no longer referenced. */
    private void persistCleanedImageKey(String projectId, String storageKey) {
        persistCleanedImageKey(projectId, storageKey, null);
    }

    /**
     * Records the cleaned canvas and ITS pixel size.
     *
     * <p>The size is not decoration. Click-to-segment turns a normalised click on the
     * canvas the user is looking at into pixel coordinates for SAM, and the cleaned
     * image is a generative edit followed by a local upscale — a different size from
     * the photo, sometimes a slightly different aspect. Scaling the click by the
     * ORIGINAL's dimensions therefore aims at a different part of the picture.
     */
    private void persistCleanedImageKey(String projectId, String storageKey, byte[] cleanedBytes) {
        int[] size = cleanedBytes == null ? null : dimensionsOf(cleanedBytes);
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setCleanedImageStorageKey(storageKey);
            p.setCleanedImageWidth(size != null ? size[0] : null);
            p.setCleanedImageHeight(size != null ? size[1] : null);
            projectRepository.save(p);
        });
    }

    /** {width, height}, or null when the bytes can't be decoded — the caller stores
     *  no size rather than a wrong one, and click-to-segment falls back to the
     *  original photo, which is exactly the old behaviour. */
    private int[] dimensionsOf(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            return img == null ? null : new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception e) {
            log.warn("Could not read the cleaned image's dimensions: {}", e.getMessage());
            return null;
        }
    }

    private void persistRawMaskKey(String projectId, String storageKey) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setRawMaskStorageKey(storageKey);
            projectRepository.save(p);
        });
    }

    private void markSegmented(String projectId) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.SEGMENTED);
            p.setFailureReason(null);
            p.setFailureStage(null);
            p.setAutoMaskFailed(false);
            p.setAiProgressNote(null);
            projectRepository.save(p);
        });
    }

    // ── Telling the user what the run is doing ───────────────────────────────
    //
    // Both halves of the pipeline walk a chain of models, and the wait is measured in
    // minutes. From the studio all of it looked the same — one spinner, no movement —
    // so a run that was patiently working through its third model was indistinguishable
    // from one that had died, and the sensible response to a dead page is to close it.
    // That is the one action that actually loses the work, so the run narrates itself.

    /**
     * Write one line of running commentary onto the project.
     *
     * <p>Its own tiny transaction on purpose: {@code segmentAsync} is not transactional
     * as a whole (it cannot be — it spends minutes inside model calls), and a note is
     * only worth anything if the polling studio can read it WHILE the run continues.
     *
     * <p>Best-effort to the point of swallowing everything. This is a progress
     * indicator; a failure to write one must never be the reason a paid run ends.
     */
    private void say(String projectId, String note) {
        try {
            projectRepository.findById(projectId).ifPresent(p -> {
                p.setAiProgressNote(note);
                projectRepository.save(p);
            });
        } catch (Exception e) {
            log.debug("Could not record progress for project {}: {}", projectId, e.toString());
        }
    }

    /**
     * The commentary for one mask attempt.
     *
     * <p>Says "looking for the walls" rather than naming the model on the first try —
     * the model's identity is noise to somebody who just wants to know it is working —
     * and only mentions a retry once there has been something to retry. The count is
     * included from then on so a long wait reads as bounded rather than open-ended.
     */
    private static String maskNote(String modelId, int attempt, int budget) {
        if (attempt <= 1) return "Finding the walls in your photo…";
        return "Still finding the walls — attempt " + attempt + " of " + budget + ".";
    }


    /**
     * Wall detection came back empty. Finish the run anyway — on the canvas that DID
     * come out — and file the report the user won't.
     *
     * <p>This used to be {@code markFailed(MASK, …)}, which is the wrong verdict on the
     * evidence. The clean succeeded, so the room the customer is standing in front of at
     * a shop counter is right there, cleaned and repainted; what is missing is three
     * masks that the studio can draw in about a minute with a tool that costs nothing.
     * Ending the project instead spent the money, produced the picture, and then refused
     * to show it — and the only thing offered was to report it and wait.
     *
     * <p>So the project ends SEGMENTED with no auto regions. That is not a lie about the
     * pipeline: it is exactly what a MANUAL-mode run produces, and {@code autoMaskFailed}
     * is the flag that tells the studio (and anyone reading the row later) that this one
     * did not CHOOSE that. Only the image credit is charged — no wall detection landed,
     * so no auto-mask credit is taken.
     *
     * <p>Then the report, and this is the part that must not be skipped. Every other
     * report in this system exists because a person looked at their room and said it was
     * wrong; nobody files one for a room that works. Left to the user, a mask model
     * failing all day would show up as nothing at all. Best-effort by design — the run is
     * already finished and correct, and a mail server or a queue being down is not a
     * reason to take the customer's cleaned photo away from them.
     *
     * @param fileReport false when the mask model was never asked (it isn't enabled in
     *                   this deployment), where there is no model behaviour to look at
     *                   and a report per project would only bury the real ones
     */
    private void handOverForManualWalls(String projectId, boolean hadCleanedCanvas,
                                        boolean fileReport) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.SEGMENTED);
            p.setFailureReason(null);
            p.setFailureStage(null);
            p.setAutoMaskFailed(true);
            p.setAiProgressNote(null);
            projectRepository.save(p);
        });
        log.warn("Auto wall detection produced nothing for project {} (cleanedCanvas={}, " +
                "reporting={}) — handing the project over for hand-marked walls",
                projectId, hadCleanedCanvas, fileReport);
        if (!fileReport) return;
        try {
            maskReportService.reportAutoMaskFailure(projectId);
        } catch (Exception e) {
            log.error("Could not raise the automatic mask report for project {}: {}",
                    projectId, e.toString());
        }
    }

    /** A failure that belongs to neither pipeline stage — configuration, mostly. */
    private void markFailed(String projectId, String reason) {
        markFailed(projectId, null, reason);
    }

    private void markFailed(String projectId, FailureStage stage, String reason) {
        log.error("Segmentation failed for project {} at stage {}: {}", projectId, stage, reason);
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.FAILED);
            p.setFailureReason(reason);
            p.setFailureStage(stage);
            // The commentary described a run in flight; the failure reason replaces it.
            // Leaving both would put "trying Nano Banana Pro…" beside "we gave up".
            p.setAiProgressNote(null);
            projectRepository.save(p);
        });
    }
}
