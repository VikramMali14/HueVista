package com.gridstore.huevista.project.service;

import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Maintenance re-snap of ALREADY STORED region masks: applies the
 * {@link MaskStraightener} boundary straightening, the {@link MaskRefiner}
 * edge snap and the {@link MaskProcessor#closeSeams inter-region seam
 * closure} — which new segmentations get inside the pipeline —
 * retroactively, to projects segmented before those steps existed.
 *
 * <p>Scope and safety:
 * <ul>
 *   <li>Only AUTO regions (main/accent/trim) are touched; MANUAL regions —
 *       click-segmented or hand-drawn — are the user's own edits and are
 *       left alone.</li>
 *   <li>Only projects with a stored cleaned canvas are processed: that canvas
 *       is the image the masks must align to, exactly as in the pipeline.</li>
 *   <li>Each snapped mask is stored under a NEW key and the region row is
 *       repointed; the old object is left in place (an orphan is cheaper than
 *       breaking a presigned URL a client is holding right now).</li>
 *   <li>Everything is best-effort per region: one bad mask counts as a
 *       failure and the pass moves on. Re-running the pass is safe — snapping
 *       an already-snapped mask is a no-op in practice.</li>
 * </ul>
 *
 * Triggered from the admin API (POST /api/admin/maintenance/resnap-masks).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaskResnapService {

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final StorageService storageService;

    /** Mirrors the pipeline's straighten kill switch, so the maintenance pass
     *  applies exactly the treatment new segmentations get. */
    @org.springframework.beans.factory.annotation.Value("${huevista.segmentation.straighten.enabled:true}")
    private boolean straightenEnabled;

    /** Mirrors the pipeline's seam-closure reach (0 disables), so the
     *  maintenance pass closes the same inter-region seams new
     *  segmentations get closed. */
    @org.springframework.beans.factory.annotation.Value("${huevista.segmentation.seam-close-px:8}")
    private int seamClosePx;

    /** Upper bound on projects per maintenance call — the admin endpoint runs
     *  synchronously, and each project costs a canvas download plus a guided
     *  filter per region. Walk a large backlog with repeated capped calls. */
    static final int MAX_PROJECTS_PER_RUN = 200;

    /** Outcome counts for one maintenance pass (also the admin response body). */
    public record ResnapSummary(int projectsExamined, int regionsResnapped,
                                int regionsSkipped, int failures) {
        ResnapSummary plus(ResnapSummary o) {
            return new ResnapSummary(
                    projectsExamined + o.projectsExamined,
                    regionsResnapped + o.regionsResnapped,
                    regionsSkipped + o.regionsSkipped,
                    failures + o.failures);
        }
    }

    /**
     * Re-snaps stored masks for up to {@code limit} projects that have a
     * cleaned canvas, oldest first.
     */
    public ResnapSummary resnapProjects(int limit) {
        int capped = Math.min(Math.max(1, limit), MAX_PROJECTS_PER_RUN);
        List<String> projectIds = projectRepository.findIdsWithCleanedImage(PageRequest.of(0, capped));
        ResnapSummary total = new ResnapSummary(0, 0, 0, 0);
        for (String projectId : projectIds) {
            total = total.plus(resnapProject(projectId));
        }
        log.info("Mask re-snap pass finished: {}", total);
        return total;
    }

    /** Re-snaps one project's stored auto masks against its cleaned canvas. */
    public ResnapSummary resnapProject(String projectId) {
        List<Region> regions = regionRepository.findAutoRegionsByProjectId(projectId);
        if (regions.isEmpty()) return new ResnapSummary(1, 0, 0, 0);

        BufferedImage canvas = loadCanvas(projectId);
        if (canvas == null) {
            // No readable cleaned canvas — nothing to align against.
            return new ResnapSummary(1, 0, regions.size(), 0);
        }

        // Pass 1: straighten + snap each mask in memory. Storing waits until
        // after the cross-region seam closure below, which needs all of a
        // project's masks together.
        record Processed(Region region, String ownerScope, byte[] mask) {}
        List<Processed> processed = new java.util.ArrayList<>();
        int skipped = 0, failures = 0;
        for (Region region : regions) {
            String stored = region.getMaskUrl();
            if (stored == null || stored.isBlank() || stored.startsWith("http://")
                    || stored.startsWith("https://")) {
                // Foreign/legacy URL, not a key in our storage — leave it be.
                skipped++;
                continue;
            }
            try {
                byte[] mask = storageService.load(stored);
                if (straightenEnabled) {
                    try {
                        mask = MaskStraightener.straighten(mask);
                    } catch (Exception e) {
                        log.warn("Straighten failed for region {} of project {}, snapping the raw mask: {}",
                                region.getId(), projectId, e.getMessage());
                    }
                }
                byte[] snapped = MaskRefiner.snapToCanvas(mask, canvas);
                // Stored keys are "<ownerScope>/<uuid>.<ext>"; keep the same scope.
                int slash = stored.indexOf('/');
                String ownerScope = slash > 0 ? stored.substring(0, slash) : stored;
                processed.add(new Processed(region, ownerScope, snapped));
            } catch (Exception e) {
                log.warn("Re-snap failed for region {} of project {}: {}",
                        region.getId(), projectId, e.getMessage());
                failures++;
            }
        }

        // Pass 2: close the unpainted seams BETWEEN this project's regions,
        // exactly as the pipeline does for new segmentations. Best-effort —
        // a failure (e.g. legacy masks at differing resolutions) keeps the
        // individually snapped masks.
        if (processed.size() >= 2 && seamClosePx > 0) {
            try {
                List<byte[]> sealed = MaskProcessor.closeSeams(
                        processed.stream().map(Processed::mask).toList(), seamClosePx);
                for (int i = 0; i < processed.size(); i++) {
                    Processed p = processed.get(i);
                    processed.set(i, new Processed(p.region(), p.ownerScope(), sealed.get(i)));
                }
            } catch (Exception e) {
                log.warn("Seam closure failed for project {}, storing unsealed masks: {}",
                        projectId, e.getMessage());
            }
        }

        // Pass 3: store and repoint.
        int resnapped = 0;
        for (Processed p : processed) {
            try {
                String newKey = storageService.store(p.mask(), p.ownerScope(),
                        (p.region().getCategory() != null
                                ? p.region().getCategory().name().toLowerCase() : "mask") + ".png",
                        "image/png");
                p.region().setMaskUrl(newKey);
                p.region().setMaskData(newKey);
                regionRepository.save(p.region());
                resnapped++;
            } catch (Exception e) {
                log.warn("Storing re-snapped mask failed for region {} of project {}: {}",
                        p.region().getId(), projectId, e.getMessage());
                failures++;
            }
        }
        return new ResnapSummary(1, resnapped, skipped, failures);
    }

    /** Loads and downsamples the project's cleaned canvas; null when absent
     *  or unreadable (the project is then skipped). */
    private BufferedImage loadCanvas(String projectId) {
        try {
            String cleanedKey = projectRepository.findCleanedImageKeyById(projectId).orElse(null);
            if (cleanedKey == null || cleanedKey.isBlank()) return null;
            byte[] bytes = storageService.load(cleanedKey);
            return MaskProcessor.downsample(MaskProcessor.decode(bytes), SegmentationService.MAX_MASK_DIM);
        } catch (Exception e) {
            log.warn("Could not load cleaned canvas for project {}: {}", projectId, e.getMessage());
            return null;
        }
    }
}
