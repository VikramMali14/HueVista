package com.gridstore.huevista.project.service;

import tools.jackson.databind.json.JsonMapper;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.service.StorageService;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Segments room images for paint visualization.
 *
 * <h3>Auto flow (segmentAsync)</h3>
 * <ol>
 *   <li><b>Wall pass:</b> Grounded SAM with positive="wall" and a negative
 *       prompt listing everything we explicitly DON'T want painted: doors,
 *       windows, furniture, electronics, light fixtures, fans, switches,
 *       outlets, AND non-paintable surfaces (tile, marble, granite, brick,
 *       stone). The model subtracts these server-side, so we get a single
 *       wall mask with the obstructions cut out.</li>
 *   <li><b>Component split:</b> Download that mask, threshold to binary, and
 *       run 8-connectivity flood fill. The largest blob becomes the MAIN
 *       wall; the second-largest becomes the ACCENT (highlighter); any
 *       remaining blobs above the noise threshold become OTHER walls. Each
 *       component is re-encoded as its own PNG and uploaded to S3 so the
 *       frontend can paint them independently.</li>
 *   <li><b>Trim pass:</b> A second Grounded SAM call with positive="window
 *       frame, door frame, baseboard, crown molding, picture rail". All
 *       detections are merged into one TRIM region. Failures here don't
 *       fail the whole segmentation — trim is best-effort.</li>
 * </ol>
 *
 * <h3>Manual flow (segmentPointAndSave)</h3>
 * User clicks a point → SAM 2 point-based segmentation → one MANUAL region.
 * Click coordinates are normalized 0–1 and scaled to the image's real pixel
 * dimensions before being sent to SAM 2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentationService {

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final StorageService storageService;
    private final RestTemplate restTemplate;
    private final JsonMapper objectMapper;
    private final WallSceneAnalyzer sceneAnalyzer;
    private final Ade20kSegmenter ade20kSegmenter;
    private final GeminiImageSegmenter geminiImageSegmenter;
    private final com.gridstore.huevista.image.repository.ImageRepository imageRepository;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    @Value("${replicate.sam2.model-version:}")
    private String sam2ModelVersion;

    @Value("${replicate.grounded-sam.model:schananas/grounded_sam}")
    private String groundedSamModel;

    @Value("${replicate.grounded-sam.model-version:}")
    private String groundedSamModelVersion;

    @Value("${replicate.grounded-sam.detect-trim:true}")
    private boolean detectTrim;

    @Value("${replicate.grounded-sam.detect-non-paintable:false}")
    private boolean detectNonPaintable;

    // --- SAM 2 auto-mode tuning (indoor vs outdoor) -------------------------
    // Outdoor facades are harder for SAM 2 auto — large uniform surfaces,
    // harsher lighting, mixed materials. Lower thresholds catch more candidates.
    @Value("${replicate.sam2.indoor.pred-iou-thresh:0.88}")
    private double indoorPredIouThresh;

    @Value("${replicate.sam2.indoor.stability-thresh:0.92}")
    private double indoorStabilityThresh;

    @Value("${replicate.sam2.outdoor.pred-iou-thresh:0.70}")
    private double outdoorPredIouThresh;

    @Value("${replicate.sam2.outdoor.stability-thresh:0.78}")
    private double outdoorStabilityThresh;

    @Value("${replicate.sam2.points-per-side:32}")
    private int sam2PointsPerSide;

    @Value("${replicate.sam2.outdoor.points-per-side:64}")
    private int outdoorSam2PointsPerSide;

    @Value("${replicate.sam2.min-mask-region-area:5000}")
    private int sam2MinMaskRegionArea;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 60;

    /** Maximum number of wall components kept (Main, Accent, plus one Other). */
    private static final int MAX_WALL_REGIONS = 3;

    /**
     * Cap on SAM 2 auto candidates fed into the Set-of-Mark composite. More
     * than this makes the annotated image too crowded for Claude to read.
     * Bumped from 25 → 40 after real-world testing showed SAM auto often
     * splits one paintable wall into 6-10 pieces, and Claude was picking
     * only the largest 1-2 — leaving most of the wall unclassified.
     */
    private static final int MAX_CANDIDATE_MASKS = 40;

    /**
     * Longest-side resolution of the annotated composite image sent to
     * Claude. 1568 matches Claude's native vision resolution — at lower
     * values the numbered mask labels become unreadable on tall portrait
     * photos, which leads to bad classifications.
     */
    private static final int CLAUDE_COMPOSITE_MAX_DIM = 1568;

    /**
     * Longest-side resolution of the ORIGINAL image when we send it
     * alongside the composite. Lets Claude cross-reference what the
     * masked colored regions actually look like in the source photo.
     */
    private static final int CLAUDE_ORIGINAL_MAX_DIM = 1024;

    /**
     * Components below this pixel area are dropped as noise / artifacts.
     * Same threshold for indoor and outdoor — turns out outdoor accent
     * areas (small painted columns, parapets between brick sections) can
     * be just as small as indoor accents, and a 15k threshold was eating
     * legitimate walls.
     */
    private static final int MIN_COMPONENT_PIXELS = 5000;

    /** Trim pieces are thinner than walls, so a smaller threshold. */
    private static final int MIN_TRIM_PIXELS = 800;

    // --- Wall positive prompts ---------------------------------------------
    // Kept simple. GroundingDINO (inside grounded_sam) matches single nouns
    // reliably and is hit-or-miss on multi-word phrases — "painted facade"
    // was returning empty masks on real building photos.
    private static final String INDOOR_WALL_PROMPT = "wall";
    private static final String OUTDOOR_WALL_PROMPT = "wall,facade";

    /**
     * INDOOR fallback for the negative_mask_prompt — used when Claude scene
     * analysis is disabled or fails. Covers openings, decor, fixtures, and
     * non-paintable wall surfaces.
     */
    private static final String INDOOR_FALLBACK_NEGATIVE = String.join(",",
            "door", "window", "windowpane",
            "painting", "picture frame", "mirror", "clock", "shelf", "television",
            "cabinet", "wardrobe", "kitchen cabinet",
            "curtain", "blinds",
            "light switch", "electrical outlet", "thermostat",
            "ceiling light", "ceiling fan", "lamp", "sconce", "chandelier",
            "pendant light", "light bulb", "spotlight",
            "smoke detector", "vent", "air conditioner", "exhaust fan",
            "tile", "ceramic tile", "marble", "granite", "exposed brick",
            "backsplash", "wallpaper", "wood paneling",
            "indoor plant"
    );

    /**
     * OUTDOOR fallback for the negative_mask_prompt — completely different
     * vocabulary from indoor. Sky, vegetation, vehicles, roof, fixtures, and
     * non-paintable exterior cladding.
     */
    private static final String OUTDOOR_FALLBACK_NEGATIVE = String.join(",",
            "sky", "clouds", "tree", "bush", "shrub", "hedge", "plant", "lawn", "grass",
            "road", "sidewalk", "driveway", "fence", "gate",
            "car", "parked car", "vehicle", "motorcycle", "bicycle",
            "door", "front door", "garage door", "window", "balcony",
            "roof", "roof tile", "shingle", "chimney",
            "drainpipe", "gutter", "downspout", "antenna", "satellite dish",
            "air conditioner", "ac unit", "electricity meter", "electrical box",
            "signage", "house number", "mailbox", "lamp post", "street light",
            "exposed brick", "stone cladding", "stone wall", "ceramic tile",
            "wood siding", "vinyl siding", "metal cladding",
            "neighboring building", "background building"
    );

    /**
     * Safety net added to Claude's exclude list so the model never forgets
     * obvious non-paintable surfaces, even if Claude omits them from its
     * scene analysis.
     */
    private static final List<String> INDOOR_SAFETY_EXCLUDES = List.of(
            "door", "window", "ceramic tile", "marble", "exposed brick", "wallpaper");
    private static final List<String> OUTDOOR_SAFETY_EXCLUDES = List.of(
            "sky", "tree", "door", "window", "exposed brick", "stone cladding", "roof");

    /**
     * Positive prompt for the dedicated non-paintable surface pass. Things
     * that look like walls but aren't repaintable in their current state —
     * subtracted from the wall mask in pixel space so the user can't paint
     * over stone cladding columns, exposed-brick parapets, or tiled
     * backsplashes.
     */
    private static final String INDOOR_NON_PAINTABLE_PROMPT = String.join(",",
            "ceramic tile", "tiled wall", "tile backsplash", "marble wall",
            "marble", "granite", "exposed brick wall", "brick wall",
            "stone wall", "stone cladding", "wallpaper", "wood paneling",
            "wood wall panel"
    );

    private static final String OUTDOOR_NON_PAINTABLE_PROMPT = String.join(",",
            "stone cladding", "stone wall", "exposed brick", "brick wall",
            "ceramic tile", "marble", "granite", "wood siding",
            "vinyl siding", "metal cladding", "metal panel",
            "glass facade", "glass wall"
    );

    /**
     * If the non-paintable mask covers more than this fraction of the frame
     * the detector misfired (it thought the whole building is brick). Skip
     * subtraction in that case so we don't wipe out the actual wall.
     */
    private static final double NON_PAINTABLE_SANITY_FRAC = 0.70;

    // --- Trim positive prompts (image-type-specific vocabulary) ------------
    private static final String INDOOR_TRIM_PROMPT = String.join(",",
            "window frame", "door frame", "baseboard", "skirting board",
            "crown molding", "picture rail", "ceiling trim", "wainscoting");
    private static final String OUTDOOR_TRIM_PROMPT = String.join(",",
            "window frame", "door frame", "fascia", "soffit", "parapet",
            "balcony railing", "trim board", "cornice");

    @Async("aiTaskExecutor")
    public void segmentAsync(String projectId, String imageUrl) {
        try {
            log.info("Starting wall segmentation (Set-of-Mark pipeline): project={}", projectId);

            if (replicateApiToken == null || replicateApiToken.isBlank()) {
                markFailed(projectId, "REPLICATE_API_TOKEN not configured");
                return;
            }

            String userId = projectRepository.findUserIdById(projectId).orElse(null);
            if (userId == null) {
                markFailed(projectId, "Project owner not found");
                return;
            }

            ImageType imageType = projectRepository.findImageTypeById(projectId)
                    .orElse(ImageType.INDOOR);
            log.info("Segmenting project {} as {}", projectId, imageType);

            regionRepository.deleteAutoRegionsByProjectId(projectId);

            UploadedImage image = loadAndEnsureDimensions(projectId);
            byte[] originalBytes = storageService.load(image.getStorageKey());

            // Step -1: NANO BANANA 2 — Gemini Image generation.
            // Send the photo to Gemini and ask for a per-category mask
            // image. One call per category (main wall, accent wall, trim).
            // When configured AND produces usable masks, skip every other
            // pipeline below. Falls through silently when not configured.
            if (tryGeminiImageSegmentation(projectId, userId, imageUrl, originalBytes)) {
                markSegmented(projectId);
                log.info("Gemini Image segmentation succeeded for project {} — skipping SAM/ADE20K", projectId);
                return;
            }

            // Step 0: ADE20K SEMANTIC SEGMENTATION (next preferred).
            // A Mask2Former/OneFormer/SegFormer model knows every pixel's
            // scene class — "wall", "windowpane", "door", "sky", "grass",
            // etc. — so we can pick the wall directly instead of asking
            // SAM 2 to guess. When this model is configured AND produces
            // a wall mask, we skip the entire SAM/Claude pipeline below.
            // Falls through silently to the legacy path when ADE20K isn't
            // configured or fails.
            if (tryAde20kSegmentation(projectId, userId, imageUrl)) {
                markSegmented(projectId);
                log.info("ADE20K segmentation succeeded for project {} — skipping SAM/Claude pipeline", projectId);
                return;
            }

            // Step 1: SAM 2 automatic mode produces ~15–30 candidate masks
            // covering every segment in the photo (wall, sky, ground, door,
            // window, AC unit, etc.). We don't yet know which is which —
            // that's Claude's job in step 3.
            List<String> maskUrls = runSam2AutoPass(projectId, imageUrl, imageType);
            if (maskUrls.isEmpty()) {
                // SAM 2 auto returned nothing — could be a transient Replicate
                // failure or genuine model output. For outdoor photos we have a
                // fully independent fallback (Claude → SAM 2 point prompts) that
                // doesn't need the auto candidates at all. Run it directly so
                // users don't get a hard failure on an intermittent issue.
                log.warn("SAM 2 auto returned no masks for project {}; trying point-based path directly", projectId);
                if (imageType == ImageType.OUTDOOR) {
                    int pointSaved = runPointBasedSegmentation(projectId, userId, imageUrl, imageType);
                    if (pointSaved > 0) {
                        markSegmented(projectId);
                        log.info("Auto-mode empty for project {}, point-based saved {} regions", projectId, pointSaved);
                        return;
                    }
                    log.warn("Point-based fallback also empty for project {}", projectId);
                }
                markFailed(projectId,
                        "SAM 2 produced no candidate masks (this can be a transient Replicate issue — try again in a minute). " +
                        "If the failure persists, the photo may be too small or low contrast. " +
                        "Try click-to-segment to mark walls manually.");
                return;
            }

            // Filter to large-enough candidates so Claude isn't overwhelmed.
            // Cap at MAX_CANDIDATES so the composite stays readable.
            List<byte[]> candidates = new ArrayList<>();
            for (String url : maskUrls) {
                try {
                    candidates.add(downloadBytes(url));
                } catch (Exception e) {
                    log.warn("Skipping mask {}: {}", url, e.getMessage());
                }
                if (candidates.size() >= MAX_CANDIDATE_MASKS) break;
            }
            log.info("SAM 2 auto: downloaded {} candidate masks for project {}",
                    candidates.size(), projectId);

            // Step 1.5: drop sky / ground / background candidates BEFORE
            // Claude sees them. Without this, Claude (and downstream color
            // expansion) reliably mis-classifies the huge sky mask as the
            // main wall — exactly what happened in the user's last test.
            candidates = filterBackgroundCandidates(candidates, projectId, imageType);
            if (candidates.isEmpty()) {
                markFailed(projectId,
                        "After filtering sky/ground, no wall-shaped candidates remained. " +
                        "Try click-to-segment.");
                return;
            }

            // Step 2: overlay all candidate masks on the original photo with
            // numeric labels. The composite is what Claude classifies.
            byte[] annotated = MaskProcessor.annotateComposite(originalBytes, candidates, CLAUDE_COMPOSITE_MAX_DIM);

            // Step 2.5: also prepare a downsampled COPY of the original to
            // send alongside the composite. Lets Claude cross-reference what
            // each colored region actually is in the source photo.
            byte[] originalForClaude;
            try {
                originalForClaude = downsampleJpegBytes(originalBytes, CLAUDE_ORIGINAL_MAX_DIM);
            } catch (Exception e) {
                log.warn("Failed to downsample original for Claude, sending composite only: {}", e.getMessage());
                originalForClaude = null;
            }

            // Step 3: Claude reads the numbers and tells us which masks are
            // main wall, accent, trim, and which to ignore.
            Optional<MaskClassification> classOpt =
                    sceneAnalyzer.classifyMasks(originalForClaude, annotated, imageType, candidates.size());
            if (classOpt.isEmpty()) {
                markFailed(projectId,
                        "Claude could not classify the candidate masks. " +
                        "Try click-to-segment to mark walls manually.");
                return;
            }
            MaskClassification mc = classOpt.get();

            // Step 3.25: color-based expansion — pull in wall fragments that
            // Claude missed but match the mean color of the wall masks.
            expandClassificationByColor(originalBytes, candidates, mc, projectId);

            if (!mc.paintable()) {
                String material = mc.wallMaterial();
                String notes = mc.notes();
                markFailed(projectId,
                        "Visible walls are " + (material != null ? material : "not a paintable surface")
                                + " and cannot be repainted. "
                                + (notes != null ? notes + " " : "")
                                + "Use click-to-segment if you want to mark a specific area.");
                return;
            }

            // Step 3.5: SUBTRACTION-BASED MAIN WALL.
            // Asking Claude to enumerate every wall fragment is unreliable on
            // photos where SAM splits the wall into 15+ pieces. Instead we
            // compute:
            //   main_wall = ALL candidates  -  exclude  -  accent  -  trim
            // Anything Claude DIDN'T explicitly assign defaults to main_wall
            // (favors inclusion). This gets full wall coverage as long as
            // Claude correctly identifies the obvious non-walls (doors,
            // windows, stone, AC units, fixtures), which it does reliably.
            List<Integer> resolvedMain = computeMainWallByExclusion(
                    candidates.size(), mc.exclude(), mc.accentWall(), mc.trim(), mc.mainWall());
            log.info("Subtraction MAIN_WALL [project={}]: claude main={} accent={} trim={} exclude={} -> resolved main={}",
                    projectId, mc.mainWall().size(), mc.accentWall().size(),
                    mc.trim().size(), mc.exclude().size(), resolvedMain.size());

            // Step 4: union the selected masks per category, clean them up,
            // upload to S3, save Region rows.
            // For wall surfaces we use color-based region growing: starting from
            // the tiny SAM 2 fragments, flood-fill outward to adjacent pixels
            // matching the wall's mean color. This turns specks into full walls.
            int displayOrder = 0;
            int saved = 0;
            // Build subtract masks for main wall: exclude + accent + trim unions
            // so any stone/window/roof that leaked into a large wall mask is removed.
            List<byte[]> mainSubtract = new ArrayList<>();
            for (int idx : mc.exclude()) { int i = idx-1; if (i>=0 && i<candidates.size()) mainSubtract.add(candidates.get(i)); }
            for (int idx : mc.accentWall()) { int i = idx-1; if (i>=0 && i<candidates.size()) mainSubtract.add(candidates.get(i)); }
            for (int idx : mc.trim()) { int i = idx-1; if (i>=0 && i<candidates.size()) mainSubtract.add(candidates.get(i)); }

            if (saveGrownRegion(projectId, userId, originalBytes, candidates, resolvedMain,
                    mainSubtract, "Main Wall", RegionCategory.MAIN_WALL, displayOrder, imageType)) {
                saved++; displayOrder++;
            }
            if (saveGrownRegion(projectId, userId, originalBytes, candidates, mc.accentWall(),
                    java.util.List.of(), "Accent Wall", RegionCategory.ACCENT_WALL, displayOrder, imageType)) {
                saved++; displayOrder++;
            }
            if (saveUnionRegion(projectId, userId, candidates, mc.trim(),
                    "Trim & Frames", RegionCategory.TRIM, displayOrder)) {
                saved++; displayOrder++;
            }

            if (saved == 0) {
                markFailed(projectId,
                        "Claude classified the photo but no paintable area remained. " +
                        "Use click-to-segment to mark walls manually.");
                return;
            }

            // Sanity check: log the size of produced regions for debugging.
            long totalForeground = regionRepository.findAutoRegionsByProjectId(projectId)
                    .stream()
                    .filter(r -> r.getCategory() == RegionCategory.MAIN_WALL || r.getCategory() == RegionCategory.ACCENT_WALL)
                    .mapToLong(r -> {
                        try {
                            byte[] b = downloadBytes(r.getMaskUrl());
                            return MaskProcessor.countForeground(b);
                        } catch (Exception e) { return 0; }
                    })
                    .sum();
            log.info("Segmentation region sizes [project={}]: total main+accent foreground pixels={}", projectId, totalForeground);

            // SUPPLEMENT WITH POINT-BASED SEGMENTATION (OUTDOOR).
            // SAM 2 auto-mode and point-mode have complementary failure modes:
            // auto-mode misses large flat painted facades, point-mode handles
            // them well but loses fine fragments. For outdoor photos we run
            // BOTH and union the resulting per-category masks so we get the
            // best of both. (Indoor stays on auto-only — point-mode on
            // interior shots over-grows into the floor/ceiling.)
            boolean isOutdoor = imageType == ImageType.OUTDOOR;
            if (isOutdoor) {
                try {
                    int supplemented = supplementExistingWithPointBased(
                            projectId, userId, imageUrl, imageType);
                    log.info("Point-based supplementation [project={}]: merged into {} regions",
                            projectId, supplemented);
                } catch (Exception e) {
                    log.warn("Point-based supplementation failed for {}, keeping auto-mode result only: {}",
                            projectId, e.getMessage(), e);
                }
            }

            // After supplementation, if the combined MAIN/ACCENT coverage is
            // still tiny, the photo just couldn't be segmented automatically.
            // Fail cleanly so the user knows to click-segment.
            if (isOutdoor) {
                long postSupplementForeground = regionRepository.findAutoRegionsByProjectId(projectId)
                        .stream()
                        .filter(r -> r.getCategory() == RegionCategory.MAIN_WALL || r.getCategory() == RegionCategory.ACCENT_WALL)
                        .mapToLong(r -> {
                            try {
                                return MaskProcessor.countForeground(downloadBytes(r.getMaskUrl()));
                            } catch (Exception e) { return 0; }
                        })
                        .sum();
                log.info("Post-supplement coverage [project={}]: {} px", projectId, postSupplementForeground);
                if (postSupplementForeground < 10000) {
                    markFailed(projectId,
                            "The building facade could not be segmented automatically. " +
                            "The photo may be too complex. Use click-to-segment to mark walls manually.");
                    return;
                }
            }

            markSegmented(projectId);
            log.info("Segmentation complete: project={} type={} regions={} candidates={}",
                    projectId, imageType, saved, candidates.size());

        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, "Segmentation failed: " + e.getMessage());
        }
    }

    /**
     * Longest-side resolution for color analysis. Color decisions don't need
     * pixel accuracy; a 512px downsample makes mean-color computation 50×
     * faster than working on the full-resolution 4000px source.
     */
    private static final int COLOR_ANALYSIS_MAX_DIM = 512;

    /**
     * Maximum RGB Euclidean distance for "this unclaimed mask is the same
     * color as main_wall, fold it in." 0–255 per channel, so the theoretical
     * max is ~441. 28 catches "same cream shade with shadow variation" while
     * rejecting "different paint color entirely" — tightened from 38 after
     * sky pixels (which can be cream/beige at sunset) were getting pulled
     * into the wall via the looser threshold.
     */
    private static final double COLOR_EXPANSION_THRESHOLD = 20.0;

    /**
     * Background-rejection thresholds. The ONLY way a mask gets dropped is
     * if it looks like sky or ground — i.e. it touches the top or bottom
     * edge AND covers a meaningful area. A large mask in the middle of the
     * frame is the building wall, NOT the background; we keep it.
     *
     * Earlier versions dropped masks above 35% coverage as "background-
     * like", which silently filtered out coherent wall masks (the building
     * wall in a phone photo often covers 30-45% of the frame). Now we only
     * drop a large mask if it ALSO touches both top AND bottom edges — that
     * geometry is only possible for a true background mask.
     */
    private static final double EDGE_BACKGROUND_FRAC = 0.10;
    private static final double FULLFRAME_BACKGROUND_FRAC = 0.65;
    private static final int BACKGROUND_EDGE_TOLERANCE_PX = 3;

    /**
     * Drops candidate masks that look like sky, ground, or a global
     * "background" mask, based on geometry alone (no Claude/SAM API needed).
     * Runs on a 512px downsampled copy so 40 masks process in &lt;1s.
     *
     * Rules:
     *   - foreground fraction &gt; 40% of frame  → background, drop
     *   - touches top edge AND fraction &gt; 15% → sky, drop
     *   - touches bottom edge AND fraction &gt; 15% → ground, drop
     */
    private List<byte[]> filterBackgroundCandidates(List<byte[]> candidates, String projectId, ImageType imageType) {
        // Outdoor buildings legitimately touch frame edges; be much more lenient.
        boolean outdoor = imageType == ImageType.OUTDOOR;
        double edgeFrac   = outdoor ? 0.25 : EDGE_BACKGROUND_FRAC;
        double fullFrac   = outdoor ? 0.80 : FULLFRAME_BACKGROUND_FRAC;
        int edgeTol       = outdoor ? 5 : BACKGROUND_EDGE_TOLERANCE_PX;
        List<byte[]> kept = new ArrayList<>(candidates.size());
        int droppedSky = 0, droppedGround = 0, droppedFullFrame = 0;
        for (byte[] bytes : candidates) {
            try {
                java.awt.image.BufferedImage raw = MaskProcessor.decode(bytes);
                java.awt.image.BufferedImage small = MaskProcessor.downsample(raw, COLOR_ANALYSIS_MAX_DIM);
                MaskProcessor.MaskStats st = MaskProcessor.stats(small, BACKGROUND_EDGE_TOLERANCE_PX);
                double frac = st.foregroundFraction();

                // SKY — touches top edge with significant area.
                if (st.touchesTop() && frac > edgeFrac) {
                    droppedSky++;
                    continue;
                }
                // GROUND — touches bottom edge with significant area.
                if (st.touchesBottom() && frac > edgeFrac) {
                    droppedGround++;
                    continue;
                }
                // FULL-FRAME BACKGROUND — covers most of the image AND touches
                // both top and bottom. For outdoor, a building can span most of
                // the frame; only drop if it covers an implausibly huge fraction.
                if (frac > fullFrac && st.touchesTop() && st.touchesBottom()) {
                    droppedFullFrame++;
                    continue;
                }
                kept.add(bytes);
            } catch (Exception e) {
                log.debug("Dropping unreadable mask for project {}: {}", projectId, e.getMessage());
            }
        }
        log.info("Background filter [project={}]: kept {} of {} (dropped {} sky, {} ground, {} full-frame)",
                projectId, kept.size(), candidates.size(), droppedSky, droppedGround, droppedFullFrame);
        return kept;
    }

    /**
     * Computes MAIN_WALL by subtraction: every candidate mask index that
     * isn't in Claude's exclude/accent/trim lists. Claude's own main_wall
     * picks are included too (as a positive signal, even though they'd be
     * captured anyway by "not in other lists"). De-duped, sorted.
     *
     * This is the load-bearing trick that makes the pipeline robust to
     * Claude under-listing wall fragments: as long as Claude correctly
     * identifies the OBVIOUS non-walls (doors, windows, stone, fixtures),
     * everything else is treated as paintable wall by default.
     */
    private List<Integer> computeMainWallByExclusion(int candidateCount,
                                                     List<Integer> exclude,
                                                     List<Integer> accent,
                                                     List<Integer> trim,
                                                     List<Integer> claudeMain) {
        java.util.Set<Integer> claimed = new java.util.HashSet<>();
        if (exclude != null) claimed.addAll(exclude);
        if (accent != null) claimed.addAll(accent);
        if (trim != null) claimed.addAll(trim);

        java.util.Set<Integer> result = new java.util.TreeSet<>();
        if (claudeMain != null) result.addAll(claudeMain);
        for (int i = 1; i <= candidateCount; i++) {
            if (!claimed.contains(i)) result.add(i);
        }
        return new java.util.ArrayList<>(result);
    }

    /**
     * Expands Claude's mask selection by color similarity: for each category
     * Claude picked, compute the mean wall color across the selected masks,
     * then pull in every unclaimed candidate mask whose mean color falls
     * within {@link #COLOR_EXPANSION_THRESHOLD} of that color. Mutates the
     * lists inside {@code mc}.
     *
     * Works on a downsampled copy of the original (and downsampled mask
     * copies) — color decisions don't need pixel accuracy. Silently no-ops
     * if anything fails; the original Claude picks always remain.
     */
    private void expandClassificationByColor(byte[] originalBytes, List<byte[]> candidates,
                                             MaskClassification mc, String projectId) {
        try {
            java.awt.image.BufferedImage originalFull = MaskProcessor.decode(originalBytes);
            java.awt.image.BufferedImage original = MaskProcessor.downsample(originalFull, COLOR_ANALYSIS_MAX_DIM);

            // Decode + downsample each candidate once.
            List<java.awt.image.BufferedImage> maskImages = new ArrayList<>(candidates.size());
            for (byte[] bytes : candidates) {
                try {
                    java.awt.image.BufferedImage decoded = MaskProcessor.decode(bytes);
                    maskImages.add(MaskProcessor.downsample(decoded, COLOR_ANALYSIS_MAX_DIM));
                } catch (Exception e) {
                    maskImages.add(null);
                }
            }

            int beforeMain = mc.mainWall().size();
            int beforeAccent = mc.accentWall().size();
            int beforeTrim = mc.trim().size();

            expandCategoryByColor(original, maskImages, mc.mainWall(), mc.accentWall(), mc.trim());
            expandCategoryByColor(original, maskImages, mc.accentWall(), mc.mainWall(), mc.trim());
            // TRIM is intentionally NOT expanded by color — trim is usually
            // a distinct color (white, dark) and applying the same heuristic
            // would pull in unrelated surfaces with similar coloring.

            int addedMain = mc.mainWall().size() - beforeMain;
            int addedAccent = mc.accentWall().size() - beforeAccent;
            int addedTrim = mc.trim().size() - beforeTrim;
            if (addedMain + addedAccent + addedTrim > 0) {
                log.info("Color expansion [project={}]: +{} main, +{} accent, +{} trim",
                        projectId, addedMain, addedAccent, addedTrim);
            }
        } catch (Exception e) {
            log.warn("Color expansion skipped for project {}: {}", projectId, e.getMessage());
        }
    }

    /**
     * For {@code target} (Claude's picks for one category), pull in any
     * unclaimed mask whose mean color matches the target's mean color.
     * Masks already in {@code otherA} or {@code otherB} are protected from
     * being stolen across categories.
     */
    private void expandCategoryByColor(java.awt.image.BufferedImage original,
                                       List<java.awt.image.BufferedImage> maskImages,
                                       List<Integer> target,
                                       List<Integer> otherA, List<Integer> otherB) {
        if (target.isEmpty()) return;

        // Collect individual mean colors from each target mask (multi-seed).
        // This lets us pull in sunlit AND shadowed wall fragments even when
        // their colors are very different.
        java.util.List<int[]> targetColors = new java.util.ArrayList<>();
        for (int idx : target) {
            int i = idx - 1;
            if (i >= 0 && i < maskImages.size() && maskImages.get(i) != null) {
                int[] c = MaskProcessor.meanColor(original, maskImages.get(i));
                if (c != null) targetColors.add(c);
            }
        }
        if (targetColors.isEmpty()) return;

        java.util.Set<Integer> claimed = new java.util.HashSet<>();
        claimed.addAll(target);
        claimed.addAll(otherA);
        claimed.addAll(otherB);

        for (int i = 0; i < maskImages.size(); i++) {
            int oneBased = i + 1;
            if (claimed.contains(oneBased)) continue;
            java.awt.image.BufferedImage m = maskImages.get(i);
            if (m == null) continue;

            int[] color = MaskProcessor.meanColor(original, m);
            if (color == null) continue;

            // Match against ANY target seed color (OR logic).
            double bestDist = Double.MAX_VALUE;
            for (int[] tc : targetColors) {
                double d = MaskProcessor.colorDistance(color, tc);
                if (d < bestDist) bestDist = d;
            }
            if (bestDist < COLOR_EXPANSION_THRESHOLD) {
                target.add(oneBased);
                claimed.add(oneBased);
                log.debug("  expansion: mask {} (color rgb={},{},{}) bestDist {} -> added",
                        oneBased, color[0], color[1], color[2], String.format("%.1f", bestDist));
            }
        }
    }

    /**
     * Unions the masks at the given indices into one mask, morph-cleans it,
     * uploads to S3, and persists a Region row. Returns false if the index
     * list is empty or the resulting mask is too small to be useful.
     */
    private boolean saveUnionRegion(String projectId, String userId,
                                    List<byte[]> candidates, List<Integer> indices,
                                    String label, RegionCategory category, int displayOrder) {
        if (indices == null || indices.isEmpty()) return false;
        try {
            List<byte[]> selected = new ArrayList<>(indices.size());
            for (int idx : indices) {
                int i = idx - 1; // Claude returns 1-based
                if (i >= 0 && i < candidates.size()) selected.add(candidates.get(i));
            }
            if (selected.isEmpty()) return false;

            byte[] union = MaskProcessor.unionMasks(selected);
            byte[] cleaned;
            try {
                cleaned = MaskProcessor.morphClean(union);
            } catch (Exception e) {
                log.warn("morphClean failed for {}, using raw union: {}", category, e.getMessage());
                cleaned = union;
            }

            String storageKey = storageService.store(
                    cleaned, userId,
                    category.name().toLowerCase() + ".png", "image/png");
            String url = storageService.getPublicUrl(storageKey);

            Region region = Region.builder()
                    .project(projectRepository.getReferenceById(projectId))
                    .label(label)
                    .category(category)
                    .maskUrl(url)
                    .maskData(url)
                    .displayOrder(displayOrder)
                    .build();
            regionRepository.save(region);
            log.info("Saved {} region for project {}: indices={} storageKey={}",
                    category, projectId, indices, storageKey);
            return true;
        } catch (Exception e) {
            log.warn("Failed to save {} region for project {}: {}", category, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Like {@link #saveUnionRegion} but applies color-based region growing
     * before saving. Starting from the tiny SAM 2 wall fragments, we flood-fill
     * outward to adjacent pixels matching the wall's mean color. This turns
     * specks into full wall surfaces for outdoor facades where SAM 2 auto
     * fragments everything.
     */
    private boolean saveGrownRegion(String projectId, String userId, byte[] originalBytes,
                                    List<byte[]> candidates, List<Integer> indices,
                                    List<byte[]> subtractMasks,
                                    String label, RegionCategory category, int displayOrder,
                                    ImageType imageType) {
        if (indices == null || indices.isEmpty()) return false;
        try {
            List<byte[]> selected = new ArrayList<>(indices.size());
            for (int idx : indices) {
                int i = idx - 1;
                if (i >= 0 && i < candidates.size()) selected.add(candidates.get(i));
            }
            if (selected.isEmpty()) return false;

            byte[] union = MaskProcessor.unionMasks(selected);
            int unionForeground = MaskProcessor.countForeground(union);
            log.info("saveGrownRegion [project={} category={}]: union of {} masks = {} foreground pixels",
                    projectId, category, selected.size(), unionForeground);

            byte[] grown;
            try {
                java.awt.image.BufferedImage original = MaskProcessor.decode(originalBytes);
                java.awt.image.BufferedImage seed = MaskProcessor.decode(union);
                int[] meanColor = MaskProcessor.meanColor(original, seed);

                // ALWAYS run color-based growing for OUTDOOR photos — SAM 2
                // tends to under-segment large painted facades even when the
                // initial seeds look "big enough", and the user is unable to
                // get acceptable wall coverage without it. For indoor scenes
                // we keep the original "only if seed tiny" rule to avoid
                // pulling in adjacent same-colored furniture.
                boolean alwaysGrow = (imageType == ImageType.OUTDOOR)
                        && category != RegionCategory.TRIM;
                if (alwaysGrow || unionForeground < 5000) {
                    // Seeds are tiny specks — use color matching confined to the
                    // building silhouette so we don't leak into ground/sky.
                    // Build silhouette = union of ALL candidate masks (after background
                    // filter, these cover the building footprint, not the surroundings).
                    java.awt.image.BufferedImage silhouette = null;
                    try {
                        byte[] silhouetteBytes = MaskProcessor.unionMasks(candidates);
                        silhouette = MaskProcessor.decode(silhouetteBytes);
                    } catch (Exception e) {
                        log.warn("Could not build silhouette for project {}, using full image", projectId);
                    }

                    // Use multi-seed: compute mean color from EACH individual mask
                    // so sunlit and shadowed walls are both captured.
                    java.util.List<int[]> seedColors = new java.util.ArrayList<>();
                    for (byte[] mb : selected) {
                        java.awt.image.BufferedImage m = MaskProcessor.decode(mb);
                        int[] c = MaskProcessor.meanColor(original, m);
                        if (c != null) seedColors.add(c);
                    }
                    if (seedColors.isEmpty() && meanColor != null) {
                        seedColors.add(meanColor);
                    }
                    log.info("saveGrownRegion [project={} category={}]: using silhouette-constrained multi-seed, {} seeds",
                            projectId, category, seedColors.size());
                    grown = MaskProcessor.maskByColorRangeMultiSeed(
                            original, silhouette, seedColors, COLOR_RANGE_THRESHOLD, 0.15);
                    int rangeForeground = MaskProcessor.countForeground(grown);
                    log.info("saveGrownRegion [project={} category={}]: after color range = {} foreground pixels",
                            projectId, category, rangeForeground);

                    // Keep only large components (walls), drop noise (specks).
                    int minArea = original.getWidth() * original.getHeight() / 200;
                    MaskProcessor.MaskAnalysis analysis = MaskProcessor.analyze(grown, minArea);
                    if (analysis.components.isEmpty()) {
                        log.warn("Color range produced no large components for {}, using raw union", category);
                        grown = union;
                    } else {
                        grown = MaskProcessor.encodeAllComponentsPng(analysis);
                        log.info("saveGrownRegion [project={} category={}]: kept {} large components (minArea={})",
                                projectId, category, analysis.components.size(), minArea);
                    }
                } else {
                    // Decent seed size — the union of SAM 2 masks is already
                    // large enough to be useful. Skip color growing to avoid
                    // pulling in stone, windows, ground, or other surfaces
                    // that happen to match one of the seed colors.
                    // Still filter out tiny noise fragments.
                    grown = union;
                    int minArea = original.getWidth() * original.getHeight() / 200;
                    MaskProcessor.MaskAnalysis analysis = MaskProcessor.analyze(grown, minArea);
                    if (!analysis.components.isEmpty()) {
                        grown = MaskProcessor.encodeAllComponentsPng(analysis);
                    }
                    log.info("saveGrownRegion [project={} category={}]: union {} px is large enough, skipping color grow, kept {} components (minArea={})",
                            projectId, category, unionForeground,
                            analysis.components.isEmpty() ? "all" : analysis.components.size(), minArea);
                }
            } catch (Exception e) {
                log.warn("Color grow/match failed for {}, using raw union: {}", category, e.getMessage(), e);
                grown = union;
            }
            // Pixel-level subtraction: remove exclude/accent/trim masks that may
            // have leaked into this category (e.g. stone within a large wall mask).
            if (subtractMasks != null && !subtractMasks.isEmpty()) {
                try {
                    byte[] excludeUnion = MaskProcessor.unionMasks(subtractMasks);
                    grown = MaskProcessor.subtract(grown, excludeUnion, 2);
                    log.info("saveGrownRegion [project={} category={}]: subtracted {} masks, remaining {} px",
                            projectId, category, subtractMasks.size(), MaskProcessor.countForeground(grown));
                } catch (Exception e) {
                    log.warn("Subtraction failed for {}, keeping grown mask: {}", category, e.getMessage());
                }
            }

            byte[] cleaned;
            try {
                cleaned = MaskProcessor.morphClean(grown);
            } catch (Exception e) {
                log.warn("morphClean failed for {}, using raw grown: {}", category, e.getMessage());
                cleaned = grown;
            }

            String storageKey = storageService.store(
                    cleaned, userId,
                    category.name().toLowerCase() + ".png", "image/png");
            String url = storageService.getPublicUrl(storageKey);

            Region region = Region.builder()
                    .project(projectRepository.getReferenceById(projectId))
                    .label(label)
                    .category(category)
                    .maskUrl(url)
                    .maskData(url)
                    .displayOrder(displayOrder)
                    .build();
            regionRepository.save(region);
            log.info("Saved grown {} region for project {}: indices={} storageKey={}",
                    category, projectId, indices, storageKey);
            return true;
        } catch (Exception e) {
            log.warn("Failed to save grown {} region for project {}: {}", category, projectId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * RGB distance threshold for color-based region growing. Seed wall fragments
     * expand to adjacent pixels within this distance. 35 catches same-paint
     * surfaces with shadow variation while stopping at stone, windows, and sky.
     */
    private static final double COLOR_GROW_THRESHOLD = 30.0;

    /**
     * RGB distance threshold for global color range matching. Used when SAM 2
     * seeds are too tiny to flood-fill from. 30 catches sunlit and shadowed
     * wall pixels across the entire facade. Stone (~20 away) and trim (~26)
     * will leak in, but pixel-level subtraction of Claude's exclude masks
     * removes them afterward. Ground/sky stay excluded via silhouette constraint.
     */
    private static final double COLOR_RANGE_THRESHOLD = 30.0;

    /**
     * Runs SAM 2 in automatic mask generation mode. The model lays a grid of
     * points across the image and produces one mask per detected segment —
     * typically 15–30 candidates. We then ask Claude (via Set-of-Mark) which
     * of those candidates are the wall.
     */
    private List<String> runSam2AutoPass(String projectId, String imageUrl, ImageType imageType) {
        try {
            boolean outdoor = imageType == ImageType.OUTDOOR;
            double predIou   = outdoor ? outdoorPredIouThresh   : indoorPredIouThresh;
            double stability = outdoor ? outdoorStabilityThresh : indoorStabilityThresh;
            int points       = outdoor ? outdoorSam2PointsPerSide : sam2PointsPerSide;

            log.info("SAM 2 auto params [project={} type={}]: pred_iou={} stability={} points={} min_area={}",
                    projectId, imageType, predIou, stability, points, sam2MinMaskRegionArea);

            Map<String, Object> input = Map.of(
                    "image", imageUrl,
                    "points_per_side", points,
                    "pred_iou_thresh", predIou,
                    "stability_score_thresh", stability,
                    "min_mask_region_area", sam2MinMaskRegionArea,
                    "use_m2m", true
            );
            String predictionId = startSam2Prediction(input);
            if (predictionId == null) {
                log.warn("SAM 2 auto: failed to start prediction for project {}", projectId);
                return List.of();
            }
            updatePredictionId(projectId, predictionId);

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("SAM 2 auto: timed out for project {}", projectId);
                return List.of();
            }
            List<String> urls = extractAllMaskUrls(result.get("output"));
            log.info("SAM 2 auto: {} masks for project {}", urls.size(), projectId);
            return urls;
        } catch (Exception e) {
            log.warn("SAM 2 auto pass failed for project {}: {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Filters Claude's exclude entries before they hit grounded_sam.
     * Now only used by the deprecated grounded_sam path (kept below for
     * reference). The current SAM 2 + points pipeline doesn't need this —
     * Claude returns coordinates, not noun phrases, so there's no risk of
     * subtracting "exterior wall" by accident.
     */
    @Deprecated
    @SuppressWarnings("unused")
    private static String sanitizeExclude(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return null;

        boolean mentionsWall = s.contains("wall") || s.contains("facade");
        if (!mentionsWall) return s;

        // Wall mentioned — keep only if qualified by a non-paintable material.
        for (String material : NON_PAINTABLE_MATERIALS) {
            if (s.contains(material)) return s;
        }
        log.debug("Dropping exclude entry that would subtract paintable wall: '{}'", raw);
        return null;
    }

    /** Material qualifiers that make a "wall" entry safe to keep in the negative prompt. */
    private static final List<String> NON_PAINTABLE_MATERIALS = List.of(
            "stone", "brick", "tile", "marble", "granite", "wallpaper",
            "wood paneling", "wood panel", "siding", "cladding", "backsplash"
    );

    // ========================================================================
    // SAM 2 + Claude-points pipeline (current auto path)
    // ========================================================================

    /**
     * Finds the project's UploadedImage and makes sure its pixel dimensions
     * are cached. Falls back to decoding the bytes from storage on first
     * use. SAM 2 needs the real pixel size to interpret Claude's normalized
     * coordinates.
     */
    private UploadedImage loadAndEnsureDimensions(String projectId) throws java.io.IOException {
        String imageId = projectRepository.findImageIdById(projectId)
                .orElseThrow(() -> new RuntimeException("Project has no image: " + projectId));
        UploadedImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new RuntimeException("Image not found: " + imageId));

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
     * Runs one SAM 2 call with positive points (Claude's hints for this
     * category) and negative points (shared exclude list — stone cladding,
     * doors, fixtures), downloads the mask, cleans it, re-uploads to our S3,
     * and persists a Region row. Returns null on any failure — the caller
     * just skips the category.
     *
     * <p>Uses {@code point_coords}/{@code point_labels} which are the actual
     * parameter names accepted by the Replicate {@code meta/sam-2} model.
     * Earlier code used {@code input_points}/{@code input_labels} which were
     * ignored by Replicate, causing the model to silently fall back to auto
     * mode and return the same building silhouette for every call.
     */
    private Region runSam2CategoryPass(String projectId, String userId, String imageUrl,
                                       int imageWidth, int imageHeight,
                                       List<WallSceneAnalysis.Point> positives,
                                       List<WallSceneAnalysis.Point> negatives,
                                       String label, RegionCategory category,
                                       int displayOrder) {
        try {
            List<List<Double>> pointCoords = new ArrayList<>();
            List<Integer> pointLabels = new ArrayList<>();
            for (WallSceneAnalysis.Point p : positives) {
                pointCoords.add(List.of(p.x() * imageWidth, p.y() * imageHeight));
                pointLabels.add(1);
            }
            for (WallSceneAnalysis.Point p : negatives) {
                pointCoords.add(List.of(p.x() * imageWidth, p.y() * imageHeight));
                pointLabels.add(0);
            }
            log.info("SAM 2 [{}] [project={}]: positives={} negatives={}",
                    category, projectId, positives.size(), negatives.size());

            Map<String, Object> input = Map.of(
                    "image", imageUrl,
                    "point_coords", pointCoords,
                    "point_labels", pointLabels
            );

            String predictionId = startSam2Prediction(input);
            if (predictionId == null) {
                log.warn("SAM 2 [{}]: failed to start prediction for project {}", category, projectId);
                return null;
            }
            updatePredictionId(projectId, predictionId);

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("SAM 2 [{}]: timed out for project {}", category, projectId);
                return null;
            }
            log.debug("SAM 2 [{}] raw output type: {}", category, result.get("output") != null ? result.get("output").getClass().getName() : "null");
            String maskUrl = extractFirstMaskUrl(result.get("output"));
            if (maskUrl == null) {
                log.warn("SAM 2 [{}]: no mask URL for project {}", category, projectId);
                return null;
            }

            byte[] rawBytes = downloadBytes(maskUrl);
            byte[] cleanBytes;
            try {
                cleanBytes = MaskProcessor.morphClean(rawBytes);
            } catch (Exception e) {
                log.warn("Morphological cleanup of {} mask failed, using raw: {}", category, e.getMessage());
                cleanBytes = rawBytes;
            }

            // SAM 2 point mode sometimes returns inverted masks (black = segment,
            // white = background). Auto-correct so white is always foreground.
            cleanBytes = MaskProcessor.ensureWhiteForeground(cleanBytes);

            String storageKey = storageService.store(
                    cleanBytes, userId,
                    category.name().toLowerCase() + ".png", "image/png");
            String url = storageService.getPublicUrl(storageKey);

            Region region = Region.builder()
                    .project(projectRepository.getReferenceById(projectId))
                    .label(label)
                    .category(category)
                    .maskUrl(url)
                    .maskData(url)
                    .displayOrder(displayOrder)
                    .build();
            Region savedRegion = regionRepository.save(region);
            log.info("Saved {} region for project {}: storageKey={}", category, projectId, storageKey);
            return savedRegion;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SAM 2 [{}] interrupted for project {}", category, projectId);
            return null;
        } catch (Exception e) {
            log.warn("SAM 2 [{}] pass failed for project {}: {}", category, projectId, e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // Point-based fallback — used when SAM 2 auto mode produces tiny fragments
    // ========================================================================

    /**
     * Fallback segmentation that uses Claude scene analysis to find point
     * coordinates on walls, then feeds those points to SAM 2 ONE AT A TIME.
     * Passing all wall points together makes SAM 2 treat the whole building
     * as one object and return a building silhouette. Single-point mode
     * isolates individual wall surfaces so we can union them afterwards.
     */
    /**
     * Runs the point-based SAM 2 pipeline and UNIONS its per-category masks
     * with whatever auto-mode already saved. Used for outdoor photos where
     * auto and point both produce partial coverage and the user's only
     * complaint is "the mask doesn't cover enough wall".
     *
     * For each category (MAIN_WALL, ACCENT_WALL, TRIM) that already has an
     * auto region, we download the existing mask, union it with the
     * point-based result for the same category, upload the merged mask,
     * and update the region's URL. If a category has only auto OR only
     * point, the surviving mask is used as-is.
     *
     * @return number of regions that received a point-based supplement
     */
    private int supplementExistingWithPointBased(String projectId, String userId,
                                                 String imageUrl, ImageType imageType) {
        Map<RegionCategory, byte[]> pointMasks =
                computePointBasedMasksPerCategory(projectId, imageUrl, imageType);
        if (pointMasks.isEmpty()) {
            log.info("Point-based supplement [project={}]: no masks produced", projectId);
            return 0;
        }

        int merged = 0;
        List<Region> autoRegions = regionRepository.findAutoRegionsByProjectId(projectId);
        Map<RegionCategory, Region> byCategory = new java.util.EnumMap<>(RegionCategory.class);
        for (Region r : autoRegions) {
            if (r.getCategory() != null && !byCategory.containsKey(r.getCategory())) {
                byCategory.put(r.getCategory(), r);
            }
        }

        for (Map.Entry<RegionCategory, byte[]> e : pointMasks.entrySet()) {
            RegionCategory cat = e.getKey();
            byte[] pointMask = e.getValue();
            try {
                Region existing = byCategory.get(cat);
                byte[] merged_bytes;
                if (existing != null) {
                    byte[] autoMask = downloadBytes(existing.getMaskUrl());
                    int autoSize = MaskProcessor.countForeground(autoMask);
                    int pointSize = MaskProcessor.countForeground(pointMask);
                    byte[] union = MaskProcessor.unionMasks(java.util.List.of(autoMask, pointMask));
                    merged_bytes = MaskProcessor.morphClean(union);
                    int finalSize = MaskProcessor.countForeground(merged_bytes);
                    log.info("Supplement {} [project={}]: auto={} px + point={} px -> union={} px",
                            cat, projectId, autoSize, pointSize, finalSize);

                    String key = storageService.store(merged_bytes, userId,
                            cat.name().toLowerCase() + "-merged.png", "image/png");
                    String url = storageService.getPublicUrl(key);
                    existing.setMaskUrl(url);
                    existing.setMaskData(url);
                    regionRepository.save(existing);
                } else {
                    // No auto region of this category — save the point result fresh.
                    byte[] cleaned = MaskProcessor.morphClean(pointMask);
                    String key = storageService.store(cleaned, userId,
                            cat.name().toLowerCase() + ".png", "image/png");
                    String url = storageService.getPublicUrl(key);
                    int order = (int) autoRegions.stream().count();
                    Region region = Region.builder()
                            .project(projectRepository.getReferenceById(projectId))
                            .label(labelFor(cat))
                            .category(cat)
                            .maskUrl(url)
                            .maskData(url)
                            .displayOrder(order)
                            .build();
                    regionRepository.save(region);
                    log.info("Supplement {} [project={}]: no auto, saved point-only {} px",
                            cat, projectId, MaskProcessor.countForeground(pointMask));
                }
                merged++;
            } catch (Exception ex) {
                log.warn("Supplement merge failed for {} on project {}: {}", cat, projectId, ex.getMessage(), ex);
            }
        }
        return merged;
    }

    /**
     * Runs ADE20K semantic segmentation, saves the wall mask (+ optional
     * trim from the railing class), and returns true on success. False if
     * the segmenter isn't configured, fails, or returns no wall pixels —
     * the caller should fall through to the SAM/Claude pipeline.
     *
     * Pipeline:
     *  1. Call Mask2Former/OneFormer/SegFormer on Replicate → per-class
     *     ADE20K masks.
     *  2. UNION the "wall" (0) and "building" (1) classes → raw paintable.
     *  3. SUBTRACT every EXCLUDE_CLASSES mask (door, window, painting,
     *     sky, ground, mirror, lights, vehicles, etc.) so we never paint
     *     over non-paintable surfaces. Dilation=1 trims a thin fringe so
     *     ADE20K's mask boundaries don't leak.
     *  4. Morph close+open to clean up speckle.
     *  5. Save as MAIN_WALL. If the model also returned a "railing"
     *     mask, save it as TRIM with displayOrder 1.
     *
     * Cost: one Replicate call (~5-10s, ~$0.005-0.02 depending on model).
     * The user explicitly requested perfect over cheap.
     */
    /**
     * Sends the photo to Gemini (Nano Banana 2 / Gemini 3 Pro Image) and
     * asks for a black-and-white mask per paint category. One Gemini call
     * per category — main wall, accent wall, trim. Saves whatever masks
     * Gemini produces; returns true if at least the main wall succeeded.
     *
     * Honest caveat: this is image GENERATION, so pixel alignment isn't
     * guaranteed. If Gemini's output is misaligned for a particular photo
     * we fall through to ADE20K and then SAM. The user requested we try
     * this path despite the alignment risk.
     *
     * Falls through silently if GEMINI_API_KEY isn't configured.
     */
    private boolean tryGeminiImageSegmentation(String projectId, String userId,
                                               String imageUrl, byte[] originalBytes) {
        try {
            if (!geminiImageSegmenter.isConfigured()) {
                log.debug("Gemini Image segmenter not configured — skipping");
                return false;
            }
            log.info("Trying Gemini Image segmentation for project {}", projectId);

            // Main wall
            Optional<byte[]> mainRaw = geminiImageSegmenter.generateMask(
                    originalBytes,
                    "the MAIN painted wall surface of this house — the dominant flat painted "
                  + "plaster/concrete that someone would repaint with a single color. EXCLUDE "
                  + "stone cladding, exposed brick, ceramic tile, marble, doors, windows, sky, "
                  + "ground, vehicles, trees, AC units, light fixtures, electrical boxes, "
                  + "drainpipes, decorative wrought iron.");
            int saved = 0;
            int displayOrder = 0;
            if (mainRaw.isPresent()) {
                byte[] cleaned = safeClean(mainRaw.get());
                int px = safeForegroundCount(cleaned);
                if (px >= 5000) {
                    String key = storageService.store(cleaned, userId, "gemini-main-wall.png", "image/png");
                    Region r = Region.builder()
                            .project(projectRepository.getReferenceById(projectId))
                            .label("Main Wall")
                            .category(RegionCategory.MAIN_WALL)
                            .maskUrl(storageService.getPublicUrl(key))
                            .maskData(storageService.getPublicUrl(key))
                            .displayOrder(displayOrder++)
                            .build();
                    regionRepository.save(r);
                    log.info("Gemini MAIN_WALL saved for project {}: {} px", projectId, px);
                    saved++;
                } else {
                    log.warn("Gemini main wall mask too small ({} px), discarding", px);
                }
            }

            // Accent wall (the "highlighter" the user mentioned)
            Optional<byte[]> accentRaw = geminiImageSegmenter.generateMask(
                    originalBytes,
                    "a SECONDARY paintable wall surface in this house that is clearly a different "
                  + "color from the main wall — a feature wall, an accent strip, or a "
                  + "perpendicular wall painted differently. If no distinct second-color wall "
                  + "is visible, return a completely BLACK image (do not invent one). "
                  + "EXCLUDE windows, doors, stone, brick, fixtures, sky, ground.");
            if (accentRaw.isPresent()) {
                byte[] cleaned = safeClean(accentRaw.get());
                int px = safeForegroundCount(cleaned);
                if (px >= 5000) {
                    String key = storageService.store(cleaned, userId, "gemini-accent-wall.png", "image/png");
                    Region r = Region.builder()
                            .project(projectRepository.getReferenceById(projectId))
                            .label("Accent Wall")
                            .category(RegionCategory.ACCENT_WALL)
                            .maskUrl(storageService.getPublicUrl(key))
                            .maskData(storageService.getPublicUrl(key))
                            .displayOrder(displayOrder++)
                            .build();
                    regionRepository.save(r);
                    log.info("Gemini ACCENT_WALL saved for project {}: {} px", projectId, px);
                    saved++;
                } else {
                    log.info("Gemini accent wall empty/tiny ({} px) — skipping (no accent in photo)", px);
                }
            }

            // Trim & frames
            Optional<byte[]> trimRaw = geminiImageSegmenter.generateMask(
                    originalBytes,
                    "the visible TRIM, borders and frames of this house — window frames, door "
                  + "frames, balcony railings, fascia under the roof, parapet edges, decorative "
                  + "banding. These are the narrow elements that are typically painted in a "
                  + "contrasting trim color. EXCLUDE the walls themselves, exclude windows "
                  + "(glass), doors (slab), stone, brick, sky, ground.");
            if (trimRaw.isPresent()) {
                byte[] cleaned = safeClean(trimRaw.get());
                int px = safeForegroundCount(cleaned);
                if (px >= 2000) {
                    String key = storageService.store(cleaned, userId, "gemini-trim.png", "image/png");
                    Region r = Region.builder()
                            .project(projectRepository.getReferenceById(projectId))
                            .label("Trim & Frames")
                            .category(RegionCategory.TRIM)
                            .maskUrl(storageService.getPublicUrl(key))
                            .maskData(storageService.getPublicUrl(key))
                            .displayOrder(displayOrder++)
                            .build();
                    regionRepository.save(r);
                    log.info("Gemini TRIM saved for project {}: {} px", projectId, px);
                    saved++;
                } else {
                    log.info("Gemini trim mask too small ({} px) — skipping", px);
                }
            }

            if (saved == 0) {
                log.info("Gemini Image returned nothing usable for project {}, falling through", projectId);
                return false;
            }
            // We treat MAIN_WALL as the load-bearing one: if Gemini produced
            // even a main wall mask we count this path as a success. (Accent
            // and trim are bonus.)
            return mainRaw.isPresent() && saved >= 1;
        } catch (Exception e) {
            log.warn("Gemini Image path failed for project {}, falling through: {}",
                    projectId, e.getMessage(), e);
            return false;
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

    private boolean tryAde20kSegmentation(String projectId, String userId, String imageUrl) {
        try {
            if (!ade20kSegmenter.isConfigured()) {
                log.debug("ADE20K segmenter not configured — skipping primary path");
                return false;
            }
            Optional<Ade20kResult> resultOpt = ade20kSegmenter.segment(imageUrl);
            if (resultOpt.isEmpty()) {
                log.info("ADE20K returned no result — falling back to SAM pipeline");
                return false;
            }
            Ade20kResult r = resultOpt.get();
            if (!r.hasWall()) {
                log.info("ADE20K returned no wall mask — falling back to SAM pipeline");
                return false;
            }

            // Build the wall mask: union of "wall" + "building".
            List<byte[]> wallMasks = new ArrayList<>();
            byte[] wallClass = r.perClassMasks().get(Ade20kResult.CLASS_WALL);
            byte[] buildingClass = r.perClassMasks().get(Ade20kResult.CLASS_BUILDING);
            if (wallClass != null) wallMasks.add(wallClass);
            if (buildingClass != null) wallMasks.add(buildingClass);
            if (wallMasks.isEmpty()) return false;

            byte[] wallUnion = MaskProcessor.unionMasks(wallMasks);
            int wallPx = MaskProcessor.countForeground(wallUnion);
            log.info("ADE20K wall union [project={}]: {} px", projectId, wallPx);

            // Subtract every exclusion class the model identified.
            List<byte[]> excludeMasks = new ArrayList<>();
            for (Integer classId : Ade20kResult.EXCLUDE_CLASSES) {
                byte[] m = r.perClassMasks().get(classId);
                if (m != null) excludeMasks.add(m);
            }
            byte[] finalWall;
            if (excludeMasks.isEmpty()) {
                finalWall = wallUnion;
            } else {
                byte[] excludeUnion = MaskProcessor.unionMasks(excludeMasks);
                try {
                    finalWall = MaskProcessor.subtract(wallUnion, excludeUnion, 1);
                    log.info("ADE20K subtracted {} exclude classes [project={}]: {} px remaining",
                            excludeMasks.size(), projectId, MaskProcessor.countForeground(finalWall));
                } catch (Exception e) {
                    log.warn("ADE20K subtract failed for {}, using raw wall union: {}", projectId, e.getMessage());
                    finalWall = wallUnion;
                }
            }

            // Morph clean — closes speckle gaps along plaster joints.
            try {
                finalWall = MaskProcessor.morphClean(finalWall);
            } catch (Exception e) {
                log.warn("Morph clean failed on ADE20K wall for {}: {}", projectId, e.getMessage());
            }

            int finalPx = MaskProcessor.countForeground(finalWall);
            if (finalPx < 5000) {
                log.warn("ADE20K wall mask too small after subtraction ({} px) — fall back to SAM", finalPx);
                return false;
            }

            // Save MAIN_WALL.
            String wallKey = storageService.store(finalWall, userId, "ade20k-main-wall.png", "image/png");
            Region wallRegion = Region.builder()
                    .project(projectRepository.getReferenceById(projectId))
                    .label("Main Wall")
                    .category(RegionCategory.MAIN_WALL)
                    .maskUrl(storageService.getPublicUrl(wallKey))
                    .maskData(storageService.getPublicUrl(wallKey))
                    .displayOrder(0)
                    .build();
            regionRepository.save(wallRegion);
            log.info("ADE20K saved MAIN_WALL for project {}: {} px", projectId, finalPx);

            // Save TRIM from railing class if present (ADE20K class 38 or 43 depending on variant).
            byte[] railing = r.perClassMasks().get(Ade20kResult.CLASS_RAILING);
            if (railing == null) railing = r.perClassMasks().get(95); // alternate "bannister" id
            if (railing != null) {
                try {
                    byte[] cleanedTrim = MaskProcessor.morphClean(railing);
                    String trimKey = storageService.store(cleanedTrim, userId, "ade20k-trim.png", "image/png");
                    Region trimRegion = Region.builder()
                            .project(projectRepository.getReferenceById(projectId))
                            .label("Trim & Frames")
                            .category(RegionCategory.TRIM)
                            .maskUrl(storageService.getPublicUrl(trimKey))
                            .maskData(storageService.getPublicUrl(trimKey))
                            .displayOrder(1)
                            .build();
                    regionRepository.save(trimRegion);
                    log.info("ADE20K saved TRIM (railing) for project {}", projectId);
                } catch (Exception e) {
                    log.warn("Failed to save ADE20K TRIM for {}: {}", projectId, e.getMessage());
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("ADE20K segmentation path failed for project {}, falling back to SAM: {}",
                    projectId, e.getMessage(), e);
            return false;
        }
    }

    private static String labelFor(RegionCategory cat) {
        return switch (cat) {
            case MAIN_WALL -> "Main Wall";
            case ACCENT_WALL -> "Accent Wall";
            case TRIM -> "Trim & Frames";
            default -> cat.name();
        };
    }

    /**
     * Runs the SAM 2 point-prompt pipeline but returns per-category mask
     * bytes instead of saving regions. Lets callers either persist directly
     * (the original fallback) or union with existing auto-saved regions
     * (the new outdoor supplementation path). All the actual SAM 2 calls
     * and unioning logic live here; both wrappers reuse it.
     */
    private Map<RegionCategory, byte[]> computePointBasedMasksPerCategory(
            String projectId, String imageUrl, ImageType imageType) {
        Map<RegionCategory, byte[]> result = new java.util.EnumMap<>(RegionCategory.class);
        try {
            Optional<WallSceneAnalysis> analysisOpt = sceneAnalyzer.analyze(imageUrl, imageType);
            if (analysisOpt.isEmpty()) {
                log.warn("Point-based: scene analysis returned empty for project {}", projectId);
                return result;
            }
            WallSceneAnalysis analysis = analysisOpt.get();
            if (!analysis.paintable()) {
                log.info("Point-based: scene flagged non-paintable for project {}", projectId);
                return result;
            }
            UploadedImage image = loadAndEnsureDimensions(projectId);
            int w = image.getWidth();
            int h = image.getHeight();
            List<WallSceneAnalysis.Point> excludes =
                    analysis.excludePoints() != null ? analysis.excludePoints() : List.of();

            if (analysis.mainWallPoints() != null && !analysis.mainWallPoints().isEmpty()) {
                List<byte[]> masks = new ArrayList<>();
                for (WallSceneAnalysis.Point p : analysis.mainWallPoints()) {
                    byte[] m = runSam2PointMask(projectId, imageUrl, w, h, p, excludes);
                    if (m != null) masks.add(m);
                }
                if (!masks.isEmpty()) {
                    result.put(RegionCategory.MAIN_WALL, MaskProcessor.unionMasks(masks));
                }
            }
            if (analysis.accentWallPoints() != null && !analysis.accentWallPoints().isEmpty()) {
                List<byte[]> masks = new ArrayList<>();
                for (WallSceneAnalysis.Point p : analysis.accentWallPoints()) {
                    byte[] m = runSam2PointMask(projectId, imageUrl, w, h, p, excludes);
                    if (m != null) masks.add(m);
                }
                if (!masks.isEmpty()) {
                    result.put(RegionCategory.ACCENT_WALL, MaskProcessor.unionMasks(masks));
                }
            }
            if (analysis.trimPoints() != null && !analysis.trimPoints().isEmpty()) {
                List<WallSceneAnalysis.Point> trimNegatives = new ArrayList<>(excludes);
                if (analysis.mainWallPoints() != null) trimNegatives.addAll(analysis.mainWallPoints());
                List<byte[]> masks = new ArrayList<>();
                for (WallSceneAnalysis.Point p : analysis.trimPoints()) {
                    byte[] m = runSam2PointMask(projectId, imageUrl, w, h, p, trimNegatives);
                    if (m != null) masks.add(m);
                }
                if (!masks.isEmpty()) {
                    result.put(RegionCategory.TRIM, MaskProcessor.unionMasks(masks));
                }
            }
        } catch (Exception e) {
            log.warn("Point-based mask computation failed for {}: {}", projectId, e.getMessage(), e);
        }
        return result;
    }

    private int runPointBasedSegmentation(String projectId, String userId, String imageUrl, ImageType imageType) {
        try {
            log.info("Starting point-based segmentation fallback: project={}", projectId);

            Optional<WallSceneAnalysis> analysisOpt = sceneAnalyzer.analyze(imageUrl, imageType);
            if (analysisOpt.isEmpty()) {
                log.warn("Point-based fallback: scene analysis failed for project {}", projectId);
                return 0;
            }
            WallSceneAnalysis analysis = analysisOpt.get();
            if (!analysis.paintable()) {
                log.warn("Point-based fallback: scene analysis says not paintable for project {}", projectId);
                return 0;
            }
            if (analysis.mainWallPoints() == null || analysis.mainWallPoints().isEmpty()) {
                log.warn("Point-based fallback: no main wall points for project {}", projectId);
                return 0;
            }

            UploadedImage image = loadAndEnsureDimensions(projectId);
            int w = image.getWidth();
            int h = image.getHeight();
            List<WallSceneAnalysis.Point> excludes =
                    analysis.excludePoints() != null ? analysis.excludePoints() : List.of();

            int displayOrder = 0;
            int saved = 0;

            // Main wall — run SAM 2 once per point, union the results.
            // Different points may land on disconnected wall surfaces
            // (e.g. left wall vs right wall separated by stone cladding).
            List<byte[]> mainMasks = new ArrayList<>();
            for (WallSceneAnalysis.Point p : analysis.mainWallPoints()) {
                byte[] m = runSam2PointMask(projectId, imageUrl, w, h, p, excludes);
                if (m != null) mainMasks.add(m);
            }
            if (!mainMasks.isEmpty()) {
                byte[] union = MaskProcessor.unionMasks(mainMasks);
                byte[] cleaned = MaskProcessor.morphClean(union);
                String key = storageService.store(cleaned, userId, "main-wall.png", "image/png");
                regionRepository.save(Region.builder()
                        .project(projectRepository.getReferenceById(projectId))
                        .label("Main Wall")
                        .category(RegionCategory.MAIN_WALL)
                        .maskUrl(storageService.getPublicUrl(key))
                        .maskData(storageService.getPublicUrl(key))
                        .displayOrder(displayOrder++)
                        .build());
                saved++;
            }

            // Accent wall — same single-point approach
            if (analysis.accentWallPoints() != null && !analysis.accentWallPoints().isEmpty()) {
                List<byte[]> accentMasks = new ArrayList<>();
                for (WallSceneAnalysis.Point p : analysis.accentWallPoints()) {
                    byte[] m = runSam2PointMask(projectId, imageUrl, w, h, p, excludes);
                    if (m != null) accentMasks.add(m);
                }
                if (!accentMasks.isEmpty()) {
                    byte[] union = MaskProcessor.unionMasks(accentMasks);
                    byte[] cleaned = MaskProcessor.morphClean(union);
                    String key = storageService.store(cleaned, userId, "accent-wall.png", "image/png");
                    regionRepository.save(Region.builder()
                            .project(projectRepository.getReferenceById(projectId))
                            .label("Accent Wall")
                            .category(RegionCategory.ACCENT_WALL)
                            .maskUrl(storageService.getPublicUrl(key))
                            .maskData(storageService.getPublicUrl(key))
                            .displayOrder(displayOrder++)
                            .build());
                    saved++;
                }
            }

            // Trim — run once per trim point, union results
            if (analysis.trimPoints() != null && !analysis.trimPoints().isEmpty()) {
                List<WallSceneAnalysis.Point> trimNegatives = new ArrayList<>(excludes);
                if (analysis.mainWallPoints() != null) trimNegatives.addAll(analysis.mainWallPoints());

                List<byte[]> trimMasks = new ArrayList<>();
                for (WallSceneAnalysis.Point p : analysis.trimPoints()) {
                    byte[] m = runSam2PointMask(projectId, imageUrl, w, h, p, trimNegatives);
                    if (m != null) trimMasks.add(m);
                }
                if (!trimMasks.isEmpty()) {
                    byte[] union = MaskProcessor.unionMasks(trimMasks);
                    byte[] cleaned = MaskProcessor.morphClean(union);
                    String key = storageService.store(cleaned, userId, "trim.png", "image/png");
                    regionRepository.save(Region.builder()
                            .project(projectRepository.getReferenceById(projectId))
                            .label("Trim & Frames")
                            .category(RegionCategory.TRIM)
                            .maskUrl(storageService.getPublicUrl(key))
                            .maskData(storageService.getPublicUrl(key))
                            .displayOrder(displayOrder++)
                            .build());
                    saved++;
                }
            }

            log.info("Point-based fallback saved {} regions for project {}", saved, projectId);
            return saved;

        } catch (Exception e) {
            log.error("Point-based segmentation fallback failed for project {}: {}", projectId, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Runs SAM 2 with a SINGLE positive point (plus optional negatives) and
     * returns the cleaned mask bytes, or null on failure. Does NOT persist
     * a Region — callers union multiple point masks before saving.
     */
    private byte[] runSam2PointMask(String projectId, String imageUrl,
                                    int imageWidth, int imageHeight,
                                    WallSceneAnalysis.Point positive,
                                    List<WallSceneAnalysis.Point> negatives) {
        try {
            List<List<Double>> pointCoords = new ArrayList<>();
            List<Integer> pointLabels = new ArrayList<>();
            pointCoords.add(List.of(positive.x() * imageWidth, positive.y() * imageHeight));
            pointLabels.add(1);
            for (WallSceneAnalysis.Point p : negatives) {
                pointCoords.add(List.of(p.x() * imageWidth, p.y() * imageHeight));
                pointLabels.add(0);
            }

            Map<String, Object> input = Map.of(
                    "image", imageUrl,
                    "point_coords", pointCoords,
                    "point_labels", pointLabels
            );

            String predictionId = startSam2Prediction(input);
            if (predictionId == null) return null;
            updatePredictionId(projectId, predictionId);

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) return null;
            String maskUrl = extractFirstMaskUrl(result.get("output"));
            if (maskUrl == null) return null;

            byte[] raw = downloadBytes(maskUrl);
            byte[] clean = MaskProcessor.morphClean(raw);
            byte[] whiteFg = MaskProcessor.ensureWhiteForeground(clean);

            // Sanity filter: SAM 2 with a single point sometimes returns the
            // "everything except this object" mask, OR Claude occasionally
            // places a point that lands on sky/ground. Either way the result
            // is a giant background-shaped mask covering the top, bottom, or
            // both edges of the image — we MUST drop it or it will pollute
            // the union with sky+ground pixels and end up painted.
            try {
                java.awt.image.BufferedImage decoded = MaskProcessor.decode(whiteFg);
                java.awt.image.BufferedImage small = MaskProcessor.downsample(decoded, 512);
                MaskProcessor.MaskStats st = MaskProcessor.stats(small, 3);
                double frac = st.foregroundFraction();
                boolean looksLikeSky = st.touchesTop() && frac > 0.10;
                boolean looksLikeGround = st.touchesBottom() && frac > 0.10;
                boolean looksLikeFullBackground = frac > 0.50 && st.touchesTop() && st.touchesBottom();
                if (looksLikeSky || looksLikeGround || looksLikeFullBackground) {
                    log.warn("Discarding point-mask for project {}: point ({},{}) returned a background-shaped mask (frac={}, top={}, bottom={})",
                            projectId, positive.x(), positive.y(),
                            String.format("%.2f", frac), st.touchesTop(), st.touchesBottom());
                    return null;
                }
            } catch (Exception e) {
                // If stats fail, prefer to keep the mask rather than drop it.
                log.debug("Sky/ground stats check failed for project {}: {}", projectId, e.getMessage());
            }

            return whiteFg;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Single-point SAM 2 failed for project {}: {}", projectId, e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // Legacy grounded_sam pipeline — kept around for reference but no longer
    // wired into segmentAsync. Will be removed once the SAM 2 + points path
    // proves stable in production.
    // ========================================================================


    /**
     * @deprecated Replaced by {@link #runSam2CategoryPass}. The grounded_sam
     * text-matching was unreliable on architectural surfaces.
     */
    @Deprecated
    private List<Region> runWallPass(String projectId, String userId, String imageUrl,
                                     String positivePrompt, String negativePrompt,
                                     int minPixelArea,
                                     byte[] nonPaintableMaskBytes)
            throws Exception {
        String predictionId = startGroundedSamPrediction(imageUrl, positivePrompt, negativePrompt);
        if (predictionId == null) {
            throw new RuntimeException("Failed to start wall segmentation prediction");
        }
        updatePredictionId(projectId, predictionId);

        Map<String, Object> result = pollUntilDone(predictionId);
        if (result == null) {
            throw new RuntimeException("Wall segmentation timed out");
        }

        String wallMaskUrl = extractGroundedSamMaskUrl(result.get("output"));
        if (wallMaskUrl == null) {
            log.warn("Wall pass returned no mask.jpg for project {}", projectId);
            return List.of();
        }

        byte[] rawMaskBytes = downloadBytes(wallMaskUrl);

        // Subtract non-paintable surfaces (stone cladding, tile, brick) in
        // pixel space. Dilation=2 grows the non-paintable mask by ~2px so we
        // remove a slight fringe along the boundary — grounded_sam edges are
        // typically a pixel or two short of the real surface boundary.
        byte[] afterSubtraction = rawMaskBytes;
        if (nonPaintableMaskBytes != null) {
            try {
                afterSubtraction = MaskProcessor.subtract(rawMaskBytes, nonPaintableMaskBytes, 2);
                log.debug("Wall pass: subtracted non-paintable surfaces for project {}", projectId);
            } catch (Exception e) {
                log.warn("Non-paintable subtraction failed for project {}, using raw wall mask: {}",
                        projectId, e.getMessage());
            }
        }

        // Morphological cleanup AFTER subtraction so we close any small holes
        // the subtraction introduced along the wall-stone boundary.
        byte[] maskBytes;
        try {
            maskBytes = MaskProcessor.morphClean(afterSubtraction);
        } catch (Exception e) {
            log.warn("Morphological cleanup failed, using subtracted mask: {}", e.getMessage());
            maskBytes = afterSubtraction;
        }

        MaskProcessor.MaskAnalysis analysis = MaskProcessor.analyze(maskBytes, minPixelArea);
        if (analysis.components.isEmpty()) {
            int allComponentArea = analysis.totalForegroundPixels();
            log.warn("Wall mask had no components above {}px for project {} " +
                            "(total foreground pixels in mask: {}, image: {}x{}, prompt='{}', negative='{}')",
                    minPixelArea, projectId, allComponentArea, analysis.width, analysis.height,
                    positivePrompt, negativePrompt);
            return List.of();
        }

        int keep = Math.min(MAX_WALL_REGIONS, analysis.components.size());
        List<Region> saved = new ArrayList<>(keep);
        for (int i = 0; i < keep; i++) {
            MaskProcessor.Component component = analysis.components.get(i);
            byte[] pngBytes = MaskProcessor.encodeComponentPng(analysis, component);
            String storageKey = storageService.store(
                    pngBytes, userId, "wall-" + (i + 1) + ".png", "image/png");
            String url = storageService.getPublicUrl(storageKey);

            RegionCategory category = (i == 0) ? RegionCategory.MAIN_WALL
                    : (i == 1) ? RegionCategory.ACCENT_WALL
                    : RegionCategory.OTHER_WALL;
            String label = (i == 0) ? "Main Wall"
                    : (i == 1) ? "Accent Wall"
                    : "Wall " + (i + 1);

            Region region = Region.builder()
                    .project(projectRepository.getReferenceById(projectId))
                    .label(label)
                    .category(category)
                    .maskUrl(url)
                    .maskData(url)
                    .displayOrder(i)
                    .build();
            saved.add(regionRepository.save(region));
            log.info("Saved {} region for project {}: storageKey={} areaPx={}",
                    category, projectId, storageKey, component.area);
        }
        return saved;
    }

    /**
     * Runs grounded_sam with a positive prompt enumerating non-paintable
     * wall surfaces (stone cladding, exposed brick, ceramic tile, marble,
     * wallpaper, wood paneling, vinyl/metal siding). Returns the resulting
     * mask bytes, or null if the pass is disabled, the call failed, or the
     * detected area was so large (>70% of frame) that the model clearly
     * misfired. The wall pass uses these bytes to surgically remove
     * non-paintable areas from its own mask.
     */
    private byte[] runNonPaintablePass(String projectId, String imageUrl, ImageType imageType) {
        if (!detectNonPaintable) {
            log.debug("Non-paintable pass disabled by config");
            return null;
        }
        try {
            String prompt = (imageType == ImageType.OUTDOOR)
                    ? OUTDOOR_NON_PAINTABLE_PROMPT
                    : INDOOR_NON_PAINTABLE_PROMPT;
            log.info("Non-paintable pass [project={}]: prompt='{}'", projectId, prompt);

            String predictionId = startGroundedSamPrediction(imageUrl, prompt, "");
            if (predictionId == null) return null;

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("Non-paintable pass: timed out for project {}", projectId);
                return null;
            }
            String url = extractGroundedSamMaskUrl(result.get("output"));
            if (url == null) {
                log.info("Non-paintable pass: nothing detected for project {} — nothing to subtract", projectId);
                return null;
            }
            byte[] bytes = downloadBytes(url);

            // Sanity check: if the detector says >70% of the frame is
            // non-paintable, it misfired (e.g. classified the whole cream
            // wall as "marble"). Skip subtraction so we don't wipe the wall.
            int foreground = MaskProcessor.countForeground(bytes);
            double frac = (double) foreground / (double) totalPixels(bytes);
            if (frac > NON_PAINTABLE_SANITY_FRAC) {
                log.warn("Non-paintable pass: covers {}% of frame for project {} — discarding (likely a false positive)",
                        String.format("%.1f", frac * 100), projectId);
                return null;
            }
            log.info("Non-paintable pass [project={}]: {} foreground pixels ({}%)",
                    projectId, foreground, String.format("%.1f", frac * 100));
            return bytes;
        } catch (Exception e) {
            log.warn("Non-paintable pass failed for project {} — continuing without subtraction: {}",
                    projectId, e.getMessage());
            return null;
        }
    }

    private static int totalPixels(byte[] maskBytes) throws java.io.IOException {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(maskBytes));
        if (img == null) throw new java.io.IOException("Could not decode mask");
        return img.getWidth() * img.getHeight();
    }

    /**
     * Runs Grounded SAM with the trim prompt and saves all detected pieces
     * as a single TRIM region. Best-effort — Grounded SAM is known to be
     * weaker on thin objects like baseboards. The prompt vocabulary differs
     * between INDOOR (baseboard, crown molding) and OUTDOOR (fascia, soffit,
     * parapet), so the caller passes in the right one.
     */
    private void runTrimPass(String projectId, String userId, String imageUrl,
                             int displayOrderStart, String trimPrompt)
            throws Exception {
        String predictionId = startGroundedSamPrediction(imageUrl, trimPrompt, "");
        if (predictionId == null) {
            log.warn("Trim pass: failed to start prediction for project {}", projectId);
            return;
        }
        Map<String, Object> result = pollUntilDone(predictionId);
        if (result == null) {
            log.warn("Trim pass: timed out for project {}", projectId);
            return;
        }
        String trimMaskUrl = extractGroundedSamMaskUrl(result.get("output"));
        if (trimMaskUrl == null) {
            log.info("Trim pass: no mask returned for project {}", projectId);
            return;
        }

        byte[] rawMaskBytes = downloadBytes(trimMaskUrl);
        byte[] maskBytes;
        try {
            maskBytes = MaskProcessor.morphClean(rawMaskBytes);
        } catch (Exception e) {
            log.warn("Morphological cleanup of trim mask failed, using raw: {}", e.getMessage());
            maskBytes = rawMaskBytes;
        }
        MaskProcessor.MaskAnalysis analysis = MaskProcessor.analyze(maskBytes, MIN_TRIM_PIXELS);
        if (analysis.components.isEmpty()) {
            log.info("Trim pass: no components above {}px for project {}",
                    MIN_TRIM_PIXELS, projectId);
            return;
        }

        byte[] combinedPng = MaskProcessor.encodeAllComponentsPng(analysis);
        String storageKey = storageService.store(
                combinedPng, userId, "trim.png", "image/png");
        String url = storageService.getPublicUrl(storageKey);

        Region region = Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label("Trim & Frames")
                .category(RegionCategory.TRIM)
                .maskUrl(url)
                .maskData(url)
                .displayOrder(displayOrderStart)
                .build();
        regionRepository.save(region);
        log.info("Saved TRIM region for project {}: storageKey={} pieces={}",
                projectId, storageKey, analysis.components.size());
    }

    /**
     * Synchronously segments a single user-clicked point and persists the
     * resulting Region as MANUAL.
     */
    public Region segmentPointAndSave(String projectId, String imageUrl,
                                      int imageWidth, int imageHeight,
                                      double x, double y, String label)
            throws InterruptedException {
        log.info("Point segmentation: project={} x={} y={} size={}x{} label={}",
                projectId, x, y, imageWidth, imageHeight, label);

        double pixelX = x * imageWidth;
        double pixelY = y * imageHeight;
        List<List<Double>> pointCoords = List.of(List.of(pixelX, pixelY));
        List<Integer> pointLabels = List.of(1);

        Map<String, Object> input = Map.of(
                "image", imageUrl,
                "point_coords", pointCoords,
                "point_labels", pointLabels
        );

        String predictionId = startSam2Prediction(input);
        if (predictionId == null) {
            throw new RuntimeException("Failed to create Replicate prediction for point segmentation");
        }
        Map<String, Object> result = pollUntilDone(predictionId);
        if (result == null) {
            throw new RuntimeException("Point segmentation timed out or failed");
        }
        String maskUrl = extractFirstMaskUrl(result.get("output"));
        if (maskUrl == null) {
            throw new RuntimeException("No mask URL in SAM 2 point segmentation output");
        }

        int displayOrder = regionRepository.countByProjectId(projectId);
        String resolvedLabel = (label != null && !label.isBlank())
                ? label
                : "Region " + (displayOrder + 1);

        Region region = Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label(resolvedLabel)
                .category(RegionCategory.MANUAL)
                .maskUrl(maskUrl)
                .maskData(maskUrl)
                .displayOrder(displayOrder)
                .build();
        return regionRepository.save(region);
    }

    // ---------------------------------------------------------------------
    // Replicate API helpers
    // ---------------------------------------------------------------------

    private String startGroundedSamPrediction(String imageUrl, String positive, String negative) {
        try {
            HttpHeaders headers = jsonHeaders();
            Map<String, Object> input = Map.of(
                    "image", imageUrl,
                    "mask_prompt", positive,
                    "negative_mask_prompt", negative,
                    "adjustment_factor", 0
            );

            boolean hasPinnedVersion = groundedSamModelVersion != null && !groundedSamModelVersion.isBlank();
            Map<String, Object> body = hasPinnedVersion
                    ? Map.of("version", groundedSamModelVersion, "input", input)
                    : Map.of("input", input);
            String endpoint = hasPinnedVersion
                    ? REPLICATE_BASE + "/predictions"
                    : REPLICATE_BASE + "/models/" + groundedSamModel + "/predictions";

            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            String id = (String) response.getBody().get("id");
            log.info("Grounded SAM prediction started: id={} positive='{}'", id, positive);
            return id;
        } catch (Exception e) {
            log.warn("Failed to start Grounded SAM prediction (positive='{}'): {}", positive, e.getMessage());
            return null;
        }
    }

    private String startSam2Prediction(Map<String, Object> input) {
        HttpHeaders headers = jsonHeaders();
        boolean hasPinnedVersion = sam2ModelVersion != null && !sam2ModelVersion.isBlank();
        Map<String, Object> body = hasPinnedVersion
                ? Map.of("version", sam2ModelVersion, "input", input)
                : Map.of("input", input);
        String endpoint = hasPinnedVersion
                ? REPLICATE_BASE + "/predictions"
                : REPLICATE_BASE + "/models/meta/sam-2/predictions";

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            return (String) response.getBody().get("id");
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
            log.debug("Replicate poll [{}]: status={}", predictionId, status);
            if ("succeeded".equals(status)) return body;
            if ("failed".equals(status) || "canceled".equals(status)) {
                log.warn("Replicate prediction {} ended with status={} error={}",
                        predictionId, status, body.get("error"));
                return null;
            }
        }
        return null;
    }

    /**
     * Picks the binary mask.jpg URL from a schananas/grounded_sam output
     * list — i.e. the raw mask, not the annotated visualization or the
     * inverted mask.
     */
    private static String extractGroundedSamMaskUrl(Object output) {
        if (!(output instanceof List<?> list)) return null;
        for (Object item : list) {
            if (!(item instanceof String url)) continue;
            String path = url.split("\\?", 2)[0];
            if (path.endsWith("/mask.jpg") || path.endsWith("/mask.png")) {
                return url;
            }
        }
        return null;
    }

    /**
     * Parses the list of individual mask URLs from a SAM 2 automatic mode
     * prediction output. The Replicate meta/sam-2 model returns either
     * { combined_mask, individual_masks: [url, ...] } when run in auto mode,
     * or a raw list of URLs in some wrappers. Returns the individual masks
     * (one URL per candidate segment).
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractAllMaskUrls(Object output) {
        List<String> out = new ArrayList<>();
        if (output instanceof Map<?, ?> map) {
            Object masks = map.get("individual_masks");
            if (masks instanceof List<?> list) {
                for (Object o : list) if (o instanceof String s) out.add(s);
            }
        } else if (output instanceof List<?> list) {
            for (Object o : list) if (o instanceof String s) out.add(s);
        }
        return out;
    }

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
     * Re-encodes JPEG bytes downscaled to {@code maxDim} on the longest side.
     * Used to send the original alongside the Set-of-Mark composite to Claude
     * at a sensible token cost.
     */
    private byte[] downsampleJpegBytes(byte[] bytes, int maxDim) throws java.io.IOException {
        java.awt.image.BufferedImage src = MaskProcessor.decode(bytes);
        java.awt.image.BufferedImage out = MaskProcessor.downsample(src, maxDim);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(out, "jpg", baos)) {
            throw new java.io.IOException("JPEG encoder not available");
        }
        return baos.toByteArray();
    }

    private byte[] downloadBytes(String url) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
        byte[] body = response.getBody();
        if (body == null) {
            throw new RuntimeException("Empty response downloading " + url);
        }
        return body;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);
        return headers;
    }

    private void updatePredictionId(String projectId, String predictionId) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setReplicatePredictionId(predictionId);
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
