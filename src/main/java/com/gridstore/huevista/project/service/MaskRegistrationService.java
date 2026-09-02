package com.gridstore.huevista.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.common.exception.StorageException;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.dto.MaskRegistrationRequest;
import com.gridstore.huevista.project.dto.MaskRegistrationResponse;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts a project's detected masks where an admin says they belong.
 *
 * <p>{@link MaskAligner} can measure how a generative colour-coded mask sits on
 * the canvas and correct it, but its search is deliberately timid — capped at a
 * few percent of the frame and discarded outright unless it beats leaving the
 * mask alone by a clear margin — and on real facades the drift is routinely
 * larger than those caps and uneven across the frame. So the automatic step is
 * now OFF by default (see {@code huevista.segmentation.mask-align.enabled}) and
 * every auto-mask ships as the model drew it. That makes this path the ONLY
 * registration a project gets, rather than the finish on the runs the search
 * declined.
 *
 * <p>This path is unaffected by that flag: the caps and validation it applies
 * are {@link MaskAligner.Fit#manual}'s, which are looser by design because a
 * person can SEE whether the wall lines up — the check every automatic threshold
 * is only a proxy for.
 *
 * <p>It takes the registration the admin bench produced,
 * re-splits the RAW colour-coded mask the run stored, and re-lands each category
 * through {@link MaskProcessor#resizeBinaryAligned} — the same single resample
 * the automatic path uses, so a hand-made fit and a measured one produce bytes
 * of the same kind. The regions themselves are updated IN PLACE: same rows, same
 * ids, same labels and applied colours, so a project that has been painted,
 * planned or put on a colour board keeps all of it and only the mask under it
 * moves.
 *
 * <p>Three things it deliberately does not do:
 *
 * <ul>
 *   <li><b>Reshape anything.</b> The only geometry applied is scale, offset and
 *       the lattice — where the model's drawing sits, never what it drew. A mask
 *       whose SHAPE is wrong (a wall the model missed, a window it filled in) is
 *       the Mask Studio brush's job, and no registration can substitute.</li>
 *   <li><b>Touch hand-drawn regions.</b> A MANUAL region was marked by a person
 *       against the canvas directly, so it is already registered to it —
 *       applying the model's correction to it would move the one mask that was
 *       never wrong.</li>
 *   <li><b>Spend a credit or re-run a model.</b> Everything here is a resample
 *       of bytes already stored.</li>
 * </ul>
 *
 * <p>Requires the project's {@code rawMaskStorageKey}: a registration is a way
 * of re-reading the model's own drawing, so a project segmented before raw-mask
 * capture shipped has nothing to re-read and is refused rather than silently
 * left alone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaskRegistrationService {

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    /** Longest side of a stored mask. Matches SegmentationService's own cap, so a
     *  re-registered mask is the same size as the one it replaces. */
    private static final int MAX_MASK_DIM = 2048;

    /** Foreground pixels a category needs to survive the split, per category.
     *  Same numbers the automatic path uses — a category that falls under one
     *  here would have been dropped there too. */
    private static final int SPLIT_MIN_PIXELS = 2000;
    private static final int MIN_MAIN_PIXELS = 5000;
    private static final int MIN_ACCENT_PIXELS = 5000;
    private static final int MIN_TRIM_PIXELS = 2000;

    /** Which split part feeds which region category. Ordered, so the log line and
     *  the bench's summary read the same way every time. */
    private static final Map<String, RegionCategory> PART_CATEGORY;

    static {
        Map<String, RegionCategory> parts = new LinkedHashMap<>();
        parts.put("main", RegionCategory.MAIN_WALL);
        parts.put("accent", RegionCategory.ACCENT_WALL);
        parts.put("trim", RegionCategory.TRIM);
        PART_CATEGORY = Collections.unmodifiableMap(parts);
    }

    /**
     * Re-lands this project's detected masks at {@code request}'s registration
     * and files it on the project so the bench can re-open it.
     *
     * @return what moved, and what the bench should show next
     */
    @Transactional
    public MaskRegistrationResponse apply(String projectId, MaskRegistrationRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));

        String rawKey = project.getRawMaskStorageKey();
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalStateException(
                    "This room has no stored colour-coded mask, so there is nothing to re-register. "
                            + "Rooms segmented before raw-mask capture shipped, and rooms whose walls "
                            + "were all drawn by hand, are in this position — re-run wall detection to "
                            + "get one.");
        }

        // Validated here rather than at the edge: the caps are MaskAligner's to
        // state, and a fold is only definable against the lattice it is in.
        MaskAligner.Warp warp = request.hasWarp()
                ? MaskAligner.Warp.of(request.getWarpCols(), request.getWarpRows(),
                        request.getWarpDu(), request.getWarpDv())
                : null;
        MaskAligner.Fit fit = MaskAligner.Fit.manual(
                request.getScaleX(), request.getScaleY(),
                request.getOffsetX(), request.getOffsetY(), warp);

        byte[] rawMask;
        try {
            rawMask = storageService.load(rawKey);
        } catch (IOException e) {
            throw new StorageException("Could not load the stored colour-coded mask", e);
        }

        // The canvas SIZES the masks, exactly as it does on the automatic path:
        // the cleaned repaint when there is one (it is what the frontend renders
        // on), otherwise the original photo. Getting this wrong would rescale
        // every mask on top of moving it.
        Canvas canvas = loadCanvas(project);

        Map<String, byte[]> parts;
        try {
            parts = MaskProcessor.splitColorCodedMask(rawMask, SPLIT_MIN_PIXELS, canvas.exterior());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "The stored colour-coded mask could not be split: " + e.getMessage(), e);
        }

        List<Region> autoRegions = regionRepository.findAutoRegionsByProjectId(projectId);
        if (autoRegions.isEmpty()) {
            throw new IllegalStateException(
                    "This room has no detected walls to re-register — every region on it was drawn "
                            + "by hand, and a hand-drawn mask is already registered to the canvas.");
        }

        List<String> moved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Map.Entry<String, RegionCategory> entry : PART_CATEGORY.entrySet()) {
            byte[] part = parts.get(entry.getKey());
            RegionCategory category = entry.getValue();
            Region region = autoRegions.stream()
                    .filter(r -> r.getCategory() == category)
                    .findFirst()
                    .orElse(null);

            if (region == null) {
                // The split found this surface but the project has no row for it.
                // Creating one here would be a second job — this endpoint moves
                // masks, it does not add walls — so say so rather than act.
                if (part != null) skipped.add(entry.getKey() + " (no region on this room)");
                continue;
            }
            if (part == null) {
                skipped.add(entry.getKey() + " (not in the model's mask)");
                continue;
            }

            byte[] landed = land(part, canvas.width(), canvas.height(), fit);
            int foreground = foregroundOf(landed);
            int floor = minPixelsFor(category);
            if (foreground < floor) {
                // A registration that pushes a wall off the frame is a mistake
                // being made, not a correction: refuse the whole apply rather
                // than write a mask with nothing in it and let the bench show a
                // blank surface as success.
                throw new IllegalStateException(
                        "At this registration the " + entry.getKey() + " mask keeps only " + foreground
                                + " pixels (needs " + floor + ") — it has been pushed off the canvas. "
                                + "Nothing was changed.");
            }

            storeOnRegion(project, region, landed, category);
            moved.add(entry.getKey());
        }

        if (moved.isEmpty()) {
            throw new IllegalStateException(
                    "Nothing on this room could be re-registered: " + String.join(", ", skipped));
        }

        project.setManualMaskRegistration(serialise(request));
        projectRepository.save(project);

        log.info("Mask registration applied by hand [project={}]: {} — moved {}{}{}",
                projectId, fit, moved,
                skipped.isEmpty() ? "" : ", skipped " + skipped,
                request.getNote() == null || request.getNote().isBlank()
                        ? "" : " — \"" + request.getNote().strip() + "\"");

        return new MaskRegistrationResponse(
                projectId, moved, skipped, canvas.width(), canvas.height(), fit.toString());
    }

    /** The registration currently filed on a project, or null when nobody has
     *  hand-placed one — which is what tells the bench to open on the mask as
     *  the automatic path left it rather than on somebody's earlier session. */
    @Transactional(readOnly = true)
    public MaskRegistrationRequest current(String projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        String json = project.getManualMaskRegistration();
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, MaskRegistrationRequest.class);
        } catch (Exception e) {
            // A registration we cannot read is not worth failing the screen for:
            // the bench opens on the as-stored mask instead, which is where a
            // person would start anyway.
            log.warn("Stored mask registration for project {} could not be read: {}",
                    projectId, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** The canvas the masks are sized and registered against, and whether the
     *  scene is an exterior — which is the only thing the split needs it for
     *  (the sky filter is meaningless indoors, where a wall may legitimately run
     *  to the top of the frame). */
    private record Canvas(int width, int height, boolean exterior) {}

    private Canvas loadCanvas(Project project) {
        byte[] bytes = null;
        String cleanedKey = project.getCleanedImageStorageKey();
        if (cleanedKey != null && !cleanedKey.isBlank()) {
            bytes = loadQuietly(cleanedKey, "cleaned canvas");
        }
        if (bytes == null && project.getImage() != null) {
            bytes = loadQuietly(project.getImage().getStorageKey(), "original photo");
        }
        if (bytes == null) {
            throw new IllegalStateException(
                    "Neither the cleaned canvas nor the original photo could be loaded, so there is "
                            + "no frame to register the mask against.");
        }

        BufferedImage canvas;
        try {
            canvas = MaskProcessor.downsample(MaskProcessor.decode(bytes), MAX_MASK_DIM);
        } catch (IOException e) {
            throw new IllegalStateException("The canvas could not be decoded: " + e.getMessage(), e);
        }

        ImageType type = project.getImage() == null ? null : project.getImage().getImageType();
        return new Canvas(canvas.getWidth(), canvas.getHeight(), type != ImageType.INDOOR);
    }

    private byte[] loadQuietly(String key, String what) {
        if (key == null || key.isBlank()) return null;
        try {
            return storageService.load(key);
        } catch (Exception e) {
            log.warn("Could not load {} ({}): {}", what, key, e.getMessage());
            return null;
        }
    }

    /** One resample: the split part, landed on the canvas at this registration.
     *  Never the two-pass "transform then resize" — a binary mask re-thresholded
     *  twice loses a pixel of edge accuracy to each pass, which is the same order
     *  as the drift being corrected. */
    private byte[] land(byte[] part, int w, int h, MaskAligner.Fit fit) {
        try {
            return fit.isIdentity()
                    ? MaskProcessor.resizeBinarySmooth(part, w, h)
                    : MaskProcessor.resizeBinaryAligned(part, w, h,
                            fit.scaleX(), fit.scaleY(), fit.offsetX(), fit.offsetY(), fit.warp());
        } catch (IOException e) {
            throw new StorageException("Could not resample a mask to the canvas", e);
        }
    }

    private int foregroundOf(byte[] mask) {
        try {
            return MaskProcessor.countForeground(mask);
        } catch (IOException e) {
            // Unreadable is not "empty": failing closed here would reject a
            // perfectly good registration over a decode hiccup.
            log.warn("Could not count foreground on a re-registered mask: {}", e.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    private static int minPixelsFor(RegionCategory category) {
        return switch (category) {
            case MAIN_WALL -> MIN_MAIN_PIXELS;
            case ACCENT_WALL -> MIN_ACCENT_PIXELS;
            default -> MIN_TRIM_PIXELS;
        };
    }

    /**
     * Points a region at its re-registered mask.
     *
     * <p>Files the mask being replaced as the region's original when nothing is
     * filed yet, on the same rule the Mask Studio's editor uses: the FIRST change
     * records what detection drew, every later one leaves the column alone. A
     * re-registration is exactly the kind of change "Restore original" exists to
     * undo, and it would be a poor trade to make the walls movable by taking the
     * way back away.
     */
    private void storeOnRegion(Project project, Region region, byte[] mask, RegionCategory category) {
        String scope = project.getUser() != null ? project.getUser().getId() : project.getId();
        String oldMask = region.getMaskUrl();
        String key;
        try {
            key = storageService.store(
                    mask, scope, category.name().toLowerCase() + "-registered.png", "image/png");
        } catch (IOException e) {
            throw new StorageException("Failed to store the re-registered mask", e);
        }

        if (region.getOriginalMaskUrl() == null && oldMask != null && !oldMask.isBlank()) {
            region.setOriginalMaskUrl(oldMask);
        }
        region.setMaskUrl(key);
        region.setMaskData(key);
        regionRepository.save(region);
    }

    private String serialise(MaskRegistrationRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            // The masks are already written and correct; losing the ability to
            // RE-OPEN the registration is a real cost but not one worth undoing
            // the work for.
            log.warn("Could not serialise the mask registration for storage: {}", e.getMessage());
            return null;
        }
    }
}
