package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.exception.ExternalServiceException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.model.MaskEnhancement;
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
 *   <li>(Optional) Send the photo to {@link ImageCleanerService} —
 *       Nano Banana Pro removes wires/bushes/clutter AND repaints the
 *       painted surfaces into the reference palette, so the canvas the masks
 *       are aligned to already looks freshly painted. Opt-in via
 *       REPLICATE_IMAGE_CLEANER_ENABLED.</li>
 *   <li>One image-edit call ({@link ReplicateMaskSegmenter}, FLUX.2 [max]
 *       by default) edits the cleaned photo into a flat colour-blocked image: RED = main
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
 *       (see {@link #postProcessMask}), uploaded to S3 and persisted as a
 *       {@link Region} row. By DEFAULT that resize is the only processing —
 *       the stored regions match the raw model output. An ADMIN can enable
 *       individual enhancement steps per run from the studio testing panel
 *       ({@link MaskEnhancement}: colour gate, morph clean, straighten,
 *       edge snap, seam close), persisted on the project.</li>
 * </ol>
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
    private final ImageRepository imageRepository;
    private final com.gridstore.huevista.billing.service.BillingService billingService;
    private final com.gridstore.huevista.account.repository.CustomerAccessCodeRepository accessCodeRepository;
    private final com.gridstore.huevista.account.repository.OrgMembershipRepository orgMembershipRepository;

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

            if (replicateApiToken == null || replicateApiToken.isBlank()) {
                markFailed(projectId, "REPLICATE_API_TOKEN not configured");
                return;
            }

            // Storage scope: a normal project is owned by a user; a guest project
            // (no user) is owned by its access code. Either way this string is only
            // used as the storage prefix for the cleaned image and mask uploads.
            // A non-null accessCodeId here also marks this as a GUEST run, whose AI
            // cost is billed to the issuing shop — but only once it succeeds (below).
            String ownerUserId = projectRepository.findUserIdById(projectId).orElse(null);
            String guestAccessCodeId = ownerUserId == null
                    ? projectRepository.findAccessCodeIdById(projectId).orElse(null) : null;
            String userId = ownerUserId != null ? ownerUserId : guestAccessCodeId;
            if (userId == null) {
                markFailed(projectId, "Project owner not found");
                return;
            }

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
            boolean skipClean = Boolean.TRUE.equals(
                    projectRepository.findSkipImageCleanById(projectId).orElse(null));
            if (skipClean) {
                log.info("Image cleaner skipped for project {} (admin cleanImage=false)", projectId);
                // Drop any cleaned canvas left by a previous run: this run's
                // masks align to the ORIGINAL photo, and a stale cleaned key
                // would make the frontend render them on the wrong canvas.
                persistCleanedImageKey(projectId, null);
            }
            String maskImageUrl = imageUrl;
            byte[] cleanedBytes = null;
            try {
                Optional<byte[]> cleanedOpt = skipClean
                        ? Optional.empty()
                        : imageCleaner.cleanImage(imageUrl, uploadedImage.getImageType());
                if (cleanedOpt.isPresent()) {
                    cleanedBytes = cleanedOpt.get();
                    String cleanedKey = storageService.store(
                            cleanedBytes, userId, "cleaned.jpg", "image/jpeg");
                    persistCleanedImageKey(projectId, cleanedKey);
                    maskImageUrl = storageService.getPublicUrl(cleanedKey);
                    log.info("ImageCleaner produced cleaned image for project {}, storageKey={}",
                            projectId, cleanedKey);
                }
            } catch (Exception e) {
                log.warn("ImageCleaner step failed for project {}, using original: {}",
                        projectId, e.getMessage());
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

            // Step 2: color-coded mask via Replicate (FLUX.2 [max] by default).
            // Scene drives the accent-wall rule: interiors always get one
            // accent wall to highlight.
            if (tryColorCodedSegmentation(projectId, userId, maskImageUrl,
                    uploadedImage.getImageType(), cleanedBytes, snapFallbackBytes,
                    uploadedImage.getWidth(), uploadedImage.getHeight())) {
                markSegmented(projectId);
                // Charge one AI preview now that walls were actually produced — a failed
                // run never costs a credit. Guest runs bill the issuing shop; a retailer's
                // own run bills the retailer (gated upfront in requestSegmentation).
                if (guestAccessCodeId != null) {
                    billGuestSegmentationIfNeeded(guestAccessCodeId);
                } else {
                    billingService.incrementAiUsage(ownerUserId);
                }
                log.info("Segmentation complete: project={}", projectId);
                return;
            }

            // Nothing worked — surface a clear failure so the UI can prompt
            // the user to click-segment manually.
            markFailed(projectId,
                    "Auto-segmentation failed — the mask model didn't produce usable masks. " +
                    "Use click-to-segment to mark walls manually.");

        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, "Segmentation failed: " + e.getMessage());
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
     * {@link #postProcessMask}) and persist each non-empty one as a Region
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
            if (!maskSegmenter.isConfigured()) {
                log.warn("Mask segmenter not configured — set " +
                        "REPLICATE_NANO_BANANA_ENABLED=true");
                return false;
            }

            // Which enhancement steps this run applies (ADMIN testing panel,
            // persisted on the project; empty = raw model masks, the default).
            java.util.Set<MaskEnhancement> enhancements = MaskEnhancement.parseCsv(
                    projectRepository.findMaskEnhancementsById(projectId).orElse(null));
            if (!enhancements.isEmpty()) {
                log.info("Mask enhancements enabled for project {}: {}", projectId, enhancements);
            }

            // The canvas SIZES the stored masks: the cleaned repaint when
            // present (it's what the frontend renders on), otherwise the
            // original photo. The enhancement steps reuse it: the colour GATE
            // needs the cleaned repaint specifically (its "freshly painted"
            // assumption doesn't hold on an arbitrary photo), while the edge
            // SNAP only needs real edges, so any faithful canvas will do.
            BufferedImage sizeCanvas = decodeCanvasForMasks(
                    cleanedBytes != null ? cleanedBytes : originalBytes);
            BufferedImage gateCanvas =
                    enhancements.contains(MaskEnhancement.COLOUR_GATE) && cleanedBytes != null
                            ? sizeCanvas : null;
            BufferedImage snapCanvas =
                    enhancements.contains(MaskEnhancement.EDGE_SNAP) ? sizeCanvas : null;

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
            ProcessedMasks masks = null;
            for (int attempt = 1; attempt <= attempts && masks == null; attempt++) {
                if (attempt > 1) {
                    log.info("Auto-mask retry {}/{} for project {} — previous generation " +
                            "produced no usable main wall", attempt, attempts, projectId);
                }
                masks = generateAndProcessMasks(projectId, imageUrl, scene,
                        gateCanvas, snapCanvas, targetW, targetH, enhancements);
            }
            if (masks == null) {
                log.info("Mask model didn't produce a usable main wall for project {} " +
                        "after {} attempt(s)", projectId, attempts);
                return false;
            }

            // Keep the accepted generation's raw colour-coded image for the
            // admin mask viewer (raw-vs-processed comparison). Best-effort:
            // a storage failure must never fail the segmentation itself.
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
            saveCategoryRegion(projectId, userId, masks.main(),
                    "Main Wall", RegionCategory.MAIN_WALL, displayOrder++,
                    defaultHexFor(RegionCategory.MAIN_WALL, scene));
            saved++;
            if (masks.accent() != null) {
                saveCategoryRegion(projectId, userId, masks.accent(),
                        "Accent Wall", RegionCategory.ACCENT_WALL, displayOrder++,
                        defaultHexFor(RegionCategory.ACCENT_WALL, scene));
                saved++;
            }
            if (masks.trim() != null) {
                saveCategoryRegion(projectId, userId, masks.trim(),
                        "Trim & Frames", RegionCategory.TRIM, displayOrder++,
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
     *  the categories were split from — persisted for the admin mask viewer's
     *  raw-vs-stored comparison. */
    private record ProcessedMasks(byte[] main, byte[] accent, byte[] trim, byte[] raw) {}

    /**
     * One model round-trip: generate the colour-coded image, split it, resize
     * every category to the canvas resolution and apply whichever enhancement
     * steps this run enabled (none by default). Returns null when the round
     * produced no usable MAIN wall (empty output, off-palette image, main
     * below the noise threshold) — nothing has been persisted at that point,
     * so the caller is free to retry with a fresh generation.
     */
    private ProcessedMasks generateAndProcessMasks(String projectId, String imageUrl,
                                                   ImageType scene, BufferedImage gateCanvas,
                                                   BufferedImage snapCanvas,
                                                   int targetW, int targetH,
                                                   java.util.Set<MaskEnhancement> enhancements) {
        Optional<byte[]> colorRaw = maskSegmenter.generateColorCodedMask(imageUrl, scene);
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

        byte[] mainBytes = postProcessMask(parts.get("main"), gateCanvas, snapCanvas,
                targetW, targetH, enhancements);
        if (mainBytes == null || safeForegroundCount(mainBytes) < 5000) {
            return null;
        }
        byte[] accentBytes = postProcessMask(parts.get("accent"), gateCanvas, snapCanvas,
                targetW, targetH, enhancements);
        if (accentBytes != null && safeForegroundCount(accentBytes) < 5000) {
            accentBytes = null;
        }
        byte[] trimBytes = postProcessMask(parts.get("trim"), gateCanvas, snapCanvas,
                targetW, targetH, enhancements);
        if (trimBytes != null && safeForegroundCount(trimBytes) < 2000) {
            trimBytes = null;
        }
        ProcessedMasks masks = new ProcessedMasks(mainBytes, accentBytes, trimBytes, colorRaw.get());
        return enhancements.contains(MaskEnhancement.CLOSE_SEAMS)
                ? sealSeams(masks, projectId) : masks;
    }

    /**
     * CLOSE_SEAMS enhancement: fills the unpainted ribbons between adjacent
     * category masks. When the other steps move each category's boundary
     * independently, coinciding boundaries end up a few pixels apart — and the
     * gap belongs to no region, rendering as a bare-canvas seam around every
     * trim band. {@link MaskProcessor#closeSeams} fills only gap pixels near
     * TWO different regions, so windows, sky and railings (bordered by one
     * region at most) are never painted over. Best-effort: any failure keeps
     * the unsealed masks.
     */
    private ProcessedMasks sealSeams(ProcessedMasks masks, String projectId) {
        if (seamClosePx <= 0) return masks;
        List<byte[]> in = new java.util.ArrayList<>();
        in.add(masks.main());
        if (masks.accent() != null) in.add(masks.accent());
        if (masks.trim() != null) in.add(masks.trim());
        if (in.size() < 2) return masks;
        try {
            List<byte[]> out = MaskProcessor.closeSeams(in, seamClosePx);
            int i = 0;
            byte[] main = out.get(i++);
            byte[] accent = masks.accent() != null ? out.get(i++) : null;
            byte[] trim = masks.trim() != null ? out.get(i) : null;
            return new ProcessedMasks(main, accent, trim, masks.raw());
        } catch (Exception e) {
            log.warn("Seam closure failed for project {}, keeping unsealed masks: {}",
                    projectId, e.getMessage());
            return masks;
        }
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

    // ========================================================================
    // REPROCESS (admin testing: re-derive regions from the STORED raw mask)
    // ========================================================================

    /**
     * Re-derives the AUTO region masks from the project's STORED raw
     * colour-coded mask with the given enhancement set — no model call, no AI
     * cost, and deterministic, so enhancement combinations can be compared on
     * the SAME model output instead of paying for a fresh (and different)
     * generation each time. This is the studio testing panel's "apply" path.
     *
     * <p>Existing region rows keep their identity and applied colours; only
     * their mask files are re-pointed (new storage key, old object left in
     * place — same convention as {@link MaskResnapService}). A category whose
     * reprocessed mask falls below the noise threshold keeps its old mask; a
     * category that clears the threshold but has no row (dropped at
     * generation time) gets one, opened in the scene's default colour.
     * Synchronous: every step is local compute.
     *
     * @return number of region masks written
     */
    public int reprocessStoredMasks(String projectId, java.util.Set<MaskEnhancement> enhancements) {
        String rawKey = projectRepository.findRawMaskKeyById(projectId)
                .filter(k -> !k.isBlank())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No raw mask is stored for this project — run wall detection once to capture it."));
        byte[] raw;
        try {
            raw = storageService.load(rawKey);
        } catch (Exception e) {
            throw new ExternalServiceException("Could not load the stored raw mask: " + e.getMessage());
        }

        ImageType scene = projectRepository.findImageTypeById(projectId).orElse(null);

        // Canvas roles mirror generation: the colour GATE needs the CLEANED
        // repaint specifically, while snap and sizing accept any faithful
        // canvas — loadSnapCanvas returns the cleaned image when present,
        // otherwise the original photo.
        BufferedImage canvas = loadSnapCanvas(projectId);
        boolean hasCleaned = projectRepository.findCleanedImageKeyById(projectId)
                .filter(k -> !k.isBlank()).isPresent();
        BufferedImage gateCanvas =
                enhancements.contains(MaskEnhancement.COLOUR_GATE) && hasCleaned && canvas != null
                        ? canvas : null;
        BufferedImage snapCanvas =
                enhancements.contains(MaskEnhancement.EDGE_SNAP) ? canvas : null;

        int targetW, targetH;
        if (canvas != null) {
            targetW = canvas.getWidth();
            targetH = canvas.getHeight();
        } else {
            try {
                BufferedImage rawImg = MaskProcessor.decode(raw);
                double scale = Math.min(1.0,
                        (double) MAX_MASK_DIM / Math.max(rawImg.getWidth(), rawImg.getHeight()));
                targetW = Math.max(1, (int) Math.round(rawImg.getWidth() * scale));
                targetH = Math.max(1, (int) Math.round(rawImg.getHeight() * scale));
            } catch (Exception e) {
                throw new ExternalServiceException("Could not decode the stored raw mask: " + e.getMessage());
            }
        }

        Map<String, byte[]> parts;
        try {
            parts = MaskProcessor.splitColorCodedMask(raw, 2000, scene != ImageType.INDOOR);
        } catch (Exception e) {
            throw new ExternalServiceException("Could not split the stored raw mask: " + e.getMessage());
        }

        byte[] main = postProcessMask(parts.get("main"), gateCanvas, snapCanvas,
                targetW, targetH, enhancements);
        if (main != null && safeForegroundCount(main) < 5000) main = null;
        byte[] accent = postProcessMask(parts.get("accent"), gateCanvas, snapCanvas,
                targetW, targetH, enhancements);
        if (accent != null && safeForegroundCount(accent) < 5000) accent = null;
        byte[] trim = postProcessMask(parts.get("trim"), gateCanvas, snapCanvas,
                targetW, targetH, enhancements);
        if (trim != null && safeForegroundCount(trim) < 2000) trim = null;

        ProcessedMasks masks = new ProcessedMasks(main, accent, trim, raw);
        if (enhancements.contains(MaskEnhancement.CLOSE_SEAMS) && main != null) {
            masks = sealSeams(masks, projectId);
        }

        String ownerUserId = projectRepository.findUserIdById(projectId).orElse(null);
        String storageScope = ownerUserId != null ? ownerUserId
                : projectRepository.findAccessCodeIdById(projectId).orElse(null);
        if (storageScope == null) {
            throw new ResourceNotFoundException("Project owner not found: " + projectId);
        }

        Map<RegionCategory, byte[]> byCategory = new java.util.EnumMap<>(RegionCategory.class);
        if (masks.main() != null) byCategory.put(RegionCategory.MAIN_WALL, masks.main());
        if (masks.accent() != null) byCategory.put(RegionCategory.ACCENT_WALL, masks.accent());
        if (masks.trim() != null) byCategory.put(RegionCategory.TRIM, masks.trim());

        int written = 0;
        for (Region region : regionRepository.findAutoRegionsByProjectId(projectId)) {
            byte[] bytes = byCategory.remove(region.getCategory());
            if (bytes == null) continue; // below threshold now — keep the old mask
            try {
                String key = storageService.store(bytes, storageScope,
                        region.getCategory().name().toLowerCase() + ".png", "image/png");
                region.setMaskUrl(key);
                region.setMaskData(key);
                regionRepository.save(region);
                written++;
            } catch (Exception e) {
                log.warn("Storing reprocessed {} mask failed for project {}: {}",
                        region.getCategory(), projectId, e.getMessage());
            }
        }
        // Categories that cleared the threshold this time but have no row yet.
        for (Map.Entry<RegionCategory, byte[]> entry : byCategory.entrySet()) {
            try {
                saveCategoryRegion(projectId, storageScope, entry.getValue(),
                        labelFor(entry.getKey()), entry.getKey(),
                        regionRepository.countByProjectId(projectId),
                        defaultHexFor(entry.getKey(), scene));
                written++;
            } catch (Exception e) {
                log.warn("Creating reprocessed {} region failed for project {}: {}",
                        entry.getKey(), projectId, e.getMessage());
            }
        }
        log.info("Reprocessed {} region mask(s) for project {} with {}", written, projectId,
                enhancements.isEmpty() ? "no enhancements (raw)" : enhancements);
        return written;
    }

    /** Display label used when the auto path (or a reprocess) creates a
     *  category's region row. */
    private static String labelFor(RegionCategory category) {
        return switch (category) {
            case MAIN_WALL -> "Main Wall";
            case ACCENT_WALL -> "Accent Wall";
            case TRIM -> "Trim & Frames";
            case OTHER_WALL -> "Other Wall";
            case MANUAL -> "Region";
        };
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
     *  the PNGs (and the colour-gate pass) fast. Package-visible: the mask
     *  maintenance re-snap decodes canvases at the same size. */
    static final int MAX_MASK_DIM = 2048;

    /** Kill switch for the mask edge snap ({@link MaskRefiner}) on MANUAL
     *  click-to-segment masks (SAM output is sometimes a pixel or two off the
     *  real surface). Auto masks are stored as the model painted them and are
     *  never snapped. Pure local compute (no external calls), defaults ON. */
    @Value("${huevista.segmentation.edge-snap.enabled:true}")
    private boolean edgeSnapEnabled;

    /** How many colour-coded generations to try before declaring auto
     *  segmentation failed. Generative models are non-deterministic, so a
     *  second roll after a dud (no usable main wall) usually lands; each
     *  extra attempt costs one more Replicate call, and only runs on failure.
     *  1 = the old single-shot behaviour. */
    @Value("${huevista.segmentation.auto-mask-attempts:2}")
    private int autoMaskAttempts;

    // Colour-gate thresholds (see MaskProcessor#restrictToPaintable): forgiving
    // enough to keep dusk-warm (spread ≤ ~60) and shadowed white walls, strict
    // enough to drop charcoal railings (luma ≈ 70), dark door leaves, window
    // glass and saturated sky/vegetation the mask bled onto.
    private static final int PAINTABLE_MAX_SPREAD = 70;
    private static final int PAINTABLE_MIN_LUMA = 78;
    private static final double PAINTABLE_MAX_REMOVED = 0.5;

    /** Max distance (px at the stored-mask resolution) each side of an
     *  unpainted seam between two adjacent region masks may be from its region
     *  for the CLOSE_SEAMS enhancement to close it. 0 disables even when the
     *  enhancement is requested. */
    @Value("${huevista.segmentation.seam-close-px:8}")
    private int seamClosePx;

    /**
     * Smooth-upscales a raw split mask to the canvas aspect/resolution — the
     * model outputs ~1K and nearest scaling to a 4K canvas shows staircase
     * blocks. By DEFAULT that resize is the only processing, so the stored
     * masks match the raw model output. The run's {@code enhancements} set
     * (ADMIN testing panel, see {@link MaskEnhancement}) can additionally
     * enable, in order: the colour gate against the CLEANED canvas, the
     * morphological cleanup, the boundary straightening and the edge snap.
     * Every step is best-effort: a failure falls back to the previous bytes.
     */
    private byte[] postProcessMask(byte[] mask, BufferedImage gateCanvas,
                                   BufferedImage snapCanvas, int w, int h,
                                   java.util.Set<MaskEnhancement> enhancements) {
        if (mask == null) return null;
        byte[] out = mask;
        try {
            out = MaskProcessor.resizeBinarySmooth(out, w, h);
        } catch (Exception e) {
            log.warn("Mask smooth-upscale to {}x{} failed, keeping model resolution: {}",
                    w, h, e.getMessage());
        }
        if (gateCanvas != null) {
            try {
                out = MaskProcessor.restrictToPaintable(out, gateCanvas,
                        PAINTABLE_MAX_SPREAD, PAINTABLE_MIN_LUMA, PAINTABLE_MAX_REMOVED);
            } catch (Exception e) {
                log.warn("Mask colour gate failed, keeping ungated mask: {}", e.getMessage());
            }
        }
        if (enhancements.contains(MaskEnhancement.MORPH_CLEAN)) {
            out = safeClean(out);
        }
        if (enhancements.contains(MaskEnhancement.STRAIGHTEN)) {
            try {
                out = MaskStraightener.straighten(out);
            } catch (Exception e) {
                log.warn("Mask straightening failed, keeping unstraightened mask: {}", e.getMessage());
            }
        }
        if (snapCanvas != null) {
            try {
                out = MaskRefiner.snapToCanvas(out, snapCanvas);
            } catch (Exception e) {
                log.warn("Mask edge snap failed, keeping unsnapped mask: {}", e.getMessage());
            }
        }
        return out;
    }

    /** Decodes a canvas image (cleaned or original) and downsamples it to the
     *  stored-mask resolution. Null input or a decode failure returns null —
     *  the caller skips the step that needed the canvas. */
    private BufferedImage decodeCanvasForMasks(byte[] cleanedBytes) {
        if (cleanedBytes == null) return null;
        try {
            return MaskProcessor.downsample(MaskProcessor.decode(cleanedBytes), MAX_MASK_DIM);
        } catch (Exception e) {
            log.warn("Could not decode cleaned canvas for mask post-processing: {}", e.getMessage());
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

    /** Morph-cleans a mask, falling back to the input if cleaning fails. */
    private byte[] safeClean(byte[] mask) {
        try {
            return MaskProcessor.morphClean(mask);
        } catch (Exception e) {
            return mask;
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
     * Charges one AI generation to the shop that issued a guest's access code, after a
     * guest run has actually produced walls. No-op for normal (user-owned) runs — pass
     * null. Best-effort: a missing code/org/owner just means no charge (the guest still
     * got their result), so billing problems never fail an otherwise-successful run.
     */
    private void billGuestSegmentationIfNeeded(String guestAccessCodeId) {
        if (guestAccessCodeId == null) return;
        try {
            String orgId = accessCodeRepository.findOrganizationIdById(guestAccessCodeId).orElse(null);
            if (orgId == null) return;
            orgMembershipRepository
                    .findUserIdsByOrganizationIdAndRole(orgId, com.gridstore.huevista.account.model.OrgMemberRole.OWNER)
                    .stream().findFirst()
                    .ifPresent(billingService::incrementAiUsage);
        } catch (Exception e) {
            log.warn("Guest segmentation succeeded but billing the shop failed (code={}): {}",
                    guestAccessCodeId, e.getMessage());
        }
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
        // (which expires) pointing at an unprocessed mask (sometimes inverted,
        // speckled, and a pixel or two off the real surface). Pull it through
        // the same fidelity steps as auto masks — fix inversion, morph-clean,
        // edge-snap to the canvas — and store the bytes in OUR storage so the
        // reference stays live. Best-effort: any failure falls back to the raw
        // URL, which is exactly the old behaviour.
        String maskRef = maskUrl;
        try {
            maskRef = persistProcessedPointMask(projectId, maskUrl);
        } catch (Exception e) {
            log.warn("Point mask post-processing failed for project {}, storing the raw SAM URL: {}",
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
     * Downloads a SAM point mask, runs it through the fidelity steps the auto
     * masks get (white-foreground fix, morphological cleanup, edge snap to the
     * canvas when available), stores the PNG under the project owner's scope
     * and returns the storage KEY (presigned fresh on every read, like auto
     * masks — a stored Replicate URL dies within the hour).
     */
    private String persistProcessedPointMask(String projectId, String samMaskUrl)
            throws java.io.IOException {
        byte[] raw = restTemplate.getForObject(samMaskUrl, byte[].class);
        if (raw == null || raw.length == 0) {
            throw new java.io.IOException("Empty SAM mask download");
        }
        byte[] out = MaskProcessor.ensureWhiteForeground(raw);
        out = safeClean(out);
        if (edgeSnapEnabled) {
            BufferedImage canvas = loadSnapCanvas(projectId);
            if (canvas != null) {
                try {
                    out = MaskRefiner.snapToCanvas(out, canvas);
                } catch (Exception e) {
                    log.warn("Point mask edge snap failed for project {}, keeping unsnapped mask: {}",
                            projectId, e.getMessage());
                }
            }
        }
        String ownerUserId = projectRepository.findUserIdById(projectId).orElse(null);
        String storageScope = ownerUserId != null ? ownerUserId
                : projectRepository.findAccessCodeIdById(projectId).orElse(null);
        if (storageScope == null) {
            throw new java.io.IOException("Project owner not found for mask storage");
        }
        return storageService.store(out, storageScope, "manual.png", "image/png");
    }

    /**
     * Loads the image a point mask should be snapped against: the cleaned
     * canvas when present (it's what the frontend renders on), otherwise the
     * original upload. Null when neither is readable — the snap is skipped.
     */
    private BufferedImage loadSnapCanvas(String projectId) {
        try {
            byte[] bytes = null;
            String cleanedKey = projectRepository.findCleanedImageKeyById(projectId).orElse(null);
            if (cleanedKey != null && !cleanedKey.isBlank()) {
                bytes = storageService.load(cleanedKey);
            } else {
                String imageId = projectRepository.findImageIdById(projectId).orElse(null);
                UploadedImage image = imageId == null ? null
                        : imageRepository.findById(imageId).orElse(null);
                if (image != null) bytes = storageService.load(image.getStorageKey());
            }
            if (bytes == null) return null;
            return MaskProcessor.downsample(MaskProcessor.decode(bytes), MAX_MASK_DIM);
        } catch (Exception e) {
            log.warn("Could not load snap canvas for project {}: {}", projectId, e.getMessage());
            return null;
        }
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

    private void persistCleanedImageKey(String projectId, String storageKey) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setCleanedImageStorageKey(storageKey);
            projectRepository.save(p);
        });
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
            projectRepository.save(p);
        });
    }

    private void markFailed(String projectId, String reason) {
        log.error("Segmentation failed for project {}: {}", projectId, reason);
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.FAILED);
            p.setFailureReason(reason);
            projectRepository.save(p);
        });
    }
}
