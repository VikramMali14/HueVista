package com.gridstore.huevista.library.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.common.exception.StorageException;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.library.FreeProjectStorage;
import com.gridstore.huevista.library.dto.*;
import com.gridstore.huevista.library.model.FreeProjectTemplate;
import com.gridstore.huevista.library.model.FreeProjectTemplateRegion;
import com.gridstore.huevista.library.model.TemplateSpace;
import com.gridstore.huevista.library.repository.FreeProjectTemplateRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The free-project library: ready-made rooms an admin publishes once and anyone
 * can open without uploading a photo or running wall detection.
 *
 * Two moves, and the asymmetry between them is the design.
 *
 * PUBLISHING is expensive and rare. It takes a project that has already been
 * through the pipeline — real photo, real masks, tuned by hand if need be — and
 * copies its files ONCE into {@code free-projects/<slug>/}. That copy is the last
 * time any image processing happens for this room, ever.
 *
 * STARTING A COPY is cheap and constant. It writes rows and nothing else: an
 * uploaded_images row, a projects row, one regions row per wall, all pointing at
 * the keys the template already owns. No bytes are read, nothing is uploaded, the
 * AI is not called, and no credit or quota is touched — a free project is free in
 * the literal sense. Ten thousand people opening the same living room cost ten
 * thousand rows of a few hundred bytes over one copy of the pixels.
 *
 * What makes that safe is {@link FreeProjectStorage#isLibraryKey}: because the
 * copies do not own their bytes, the ordinary project cleanup must never delete
 * them, and it checks before it does.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FreeProjectLibraryService {

    private final FreeProjectTemplateRepository templateRepository;
    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final ImageRepository imageRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final AuditService auditService;

    /**
     * This bean through the proxy, so a bulk delete gets one transaction PER
     * template. Calling deleteTemplate() directly would run every one inside the
     * caller's transaction, where a single failure takes the whole selection with it.
     */
    @Autowired
    @Lazy
    private FreeProjectLibraryService self;

    /** How many of the admin's own projects the publish picker offers. */
    private static final int PUBLISHABLE_PAGE_SIZE = 60;

    // ─── Reading ─────────────────────────────────────────────────────────────

    /**
     * The gallery. URLs are minted per call rather than stored: in S3 mode
     * {@code getPublicUrl} presigns, and a saved link would be dead within the hour.
     */
    @Transactional(readOnly = true)
    public List<FreeProjectTemplateResponse> listTemplates(boolean includeUnpublished) {
        List<FreeProjectTemplate> templates = includeUnpublished
                ? templateRepository.findAllByOrderBySpaceAscRoomKeyAscDisplayOrderAscTitleAsc()
                : templateRepository.findByPublishedTrueOrderBySpaceAscRoomKeyAscDisplayOrderAscTitleAsc();
        // One grouped query for the whole shelf rather than a count per card.
        Map<String, Long> copies = countCopies(templates.stream()
                .map(FreeProjectTemplate::getImageStorageKey).toList());
        return templates.stream()
                .map(t -> toResponse(t, copies.getOrDefault(t.getImageStorageKey(), 0L)))
                .toList();
    }

    /**
     * The public gallery: published rooms only, in shelf order.
     *
     * A separate method from {@link #listTemplates} rather than that one with the
     * flag set, because the two are answering different people. This one can never
     * be asked for the unpublished shelf — there is no parameter to get it wrong
     * with — and it maps to the trimmed {@link PublicFreeProjectResponse}, so a field
     * added to the admin DTO does not appear on the marketing site by default.
     */
    @Transactional(readOnly = true)
    public List<PublicFreeProjectResponse> listPublished() {
        return templateRepository.findByPublishedTrueOrderBySpaceAscRoomKeyAscDisplayOrderAscTitleAsc()
                .stream()
                .map(t -> PublicFreeProjectResponse.from(t, publicImageUrl(t)))
                .toList();
    }

    /** One published room by slug, for its own page. Hidden ones read as absent. */
    @Transactional(readOnly = true)
    public PublicFreeProjectResponse getPublished(String slug) {
        return templateRepository.findBySlug(slug)
                .filter(FreeProjectTemplate::isPublished)
                .map(t -> PublicFreeProjectResponse.from(t, publicImageUrl(t)))
                .orElseThrow(() -> new ResourceNotFoundException("No published room: " + slug));
    }

    /**
     * The picture to show for a template, as an ANONYMOUS browser can load it.
     *
     * The cleaned photo when there is one, since that is what the masks line up with
     * and what the studio itself draws.
     *
     * The rewrite at the end is the part that matters. In S3 mode
     * {@code getPublicUrl} presigns and the link works for anybody. In local-storage
     * mode it returns {@code /api/images/files/<key>}, which is behind
     * authentication — so on the public gallery every photograph would 403 for
     * exactly the visitors the page exists for. Those keys are pointed at
     * {@link com.gridstore.huevista.library.controller.FreeProjectController}'s own
     * public file route instead, which serves library keys and nothing else.
     */
    private String publicImageUrl(FreeProjectTemplate t) {
        String key = t.getCleanedImageStorageKey() != null && !t.getCleanedImageStorageKey().isBlank()
                ? t.getCleanedImageStorageKey()
                : t.getImageStorageKey();
        String url = storageService.getPublicUrl(key);
        return url != null && url.startsWith("http") ? url : "/api/free-projects/files/" + key;
    }

    /** Live copies per template photo, keyed by storage key. Absent = none. */
    private Map<String, Long> countCopies(List<String> imageKeys) {
        if (imageKeys.isEmpty()) return Map.of();
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : imageRepository.countByStorageKeys(imageKeys)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** The admin's own projects, marked with whether each one can be published. */
    @Transactional(readOnly = true)
    public List<PublishableProjectResponse> listPublishableProjects(String adminUserId) {
        return projectRepository
                .findByUserIdWithImage(adminUserId, PageRequest.of(0, PUBLISHABLE_PAGE_SIZE))
                .stream()
                .map(p -> {
                    List<Region> regions = regionRepository.findByProjectIdOrderByDisplayOrderAsc(p.getId());
                    long withMasks = regions.stream().filter(r -> hasMask(r.getMaskUrl())).count();
                    String reason = null;
                    if (withMasks == 0) {
                        reason = p.getStatus() == ProjectStatus.SEGMENTED
                                ? "No walls on this project yet — mark some in the studio first."
                                : "Not segmented yet — run wall detection, or mark the walls by hand.";
                    }
                    return PublishableProjectResponse.builder()
                            .id(p.getId())
                            .name(p.getName())
                            .roomType(p.getRoomType())
                            .status(p.getStatus().name())
                            .imageUrl(storageService.getPublicUrl(p.getImage().getStorageKey()))
                            .regionCount((int) withMasks)
                            .eligible(withMasks > 0)
                            .ineligibleReason(reason)
                            .updatedAt(p.getUpdatedAt())
                            .build();
                })
                .toList();
    }

    // ─── Publishing ──────────────────────────────────────────────────────────

    /**
     * Freeze one of the admin's projects into the library.
     *
     * The files are COPIED rather than referenced, so the template stops depending
     * on the project it came from: the admin can delete that project, or keep
     * repainting it, without the shelf changing under anyone.
     */
    @Transactional
    public FreeProjectTemplateResponse publishFromProject(String adminUserId, PublishTemplateRequest request) {
        Project project = projectRepository.findByIdAndUserId(request.getProjectId(), adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + request.getProjectId()));

        List<Region> regions = regionRepository.findByProjectIdOrderByDisplayOrderAsc(project.getId())
                .stream().filter(r -> hasMask(r.getMaskUrl())).toList();
        if (regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "This project has no walls to copy. Segment it (or mark the walls by hand) before publishing.");
        }

        TemplateSpace space = parseSpace(request.getSpace());
        String slug = uniqueSlug(request.getSlug() != null && !request.getSlug().isBlank()
                ? request.getSlug()
                : slugify(request.getTitle()));
        String folder = FreeProjectStorage.folderFor(slug);

        // Copy the photo first: it is the one file the template cannot do without, so
        // failing here leaves nothing behind but a rolled-back transaction.
        UploadedImage sourceImage = project.getImage();
        byte[] photo = read(sourceImage.getStorageKey(), "photo");
        String imageKey = write(photo, folder, "source" + extensionOf(sourceImage.getOriginalFilename(), ".jpg"),
                sourceImage.getContentType());

        String cleanedKey = null;
        if (project.getCleanedImageStorageKey() != null && !project.getCleanedImageStorageKey().isBlank()) {
            cleanedKey = write(read(project.getCleanedImageStorageKey(), "cleaned photo"),
                    folder, "cleaned.png", "image/png");
        }

        // Dimensions are denormalised onto the template so starting a copy never has
        // to open the file. Read them here, where the bytes are already in hand.
        Integer width = sourceImage.getWidth();
        Integer height = sourceImage.getHeight();
        if (width == null || height == null) {
            int[] wh = dimensionsOf(photo);
            if (wh != null) {
                width = wh[0];
                height = wh[1];
            }
        }

        FreeProjectTemplate template = FreeProjectTemplate.builder()
                .slug(slug)
                .title(request.getTitle().trim())
                .space(space)
                .roomKey(request.getRoomKey().trim().toUpperCase(Locale.ROOT))
                .roomLabel(resolveRoomLabel(request))
                .description(blankToNull(request.getDescription()))
                .imageStorageKey(imageKey)
                .imageContentType(sourceImage.getContentType())
                .imageFileSize(photo.length)
                .imageWidth(width)
                .imageHeight(height)
                .imageType(sourceImage.getImageType() != null ? sourceImage.getImageType() : ImageType.UNKNOWN)
                .cleanedImageStorageKey(cleanedKey)
                .published(request.getPublished() == null || request.getPublished())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .sourceProjectId(project.getId())
                .createdByUserId(adminUserId)
                .build();

        int order = 0;
        for (Region region : regions) {
            byte[] mask = read(storageKeyOf(region.getMaskUrl()), "mask for region " + region.getId());
            String maskKey = write(mask, folder, "mask-" + order + ".png", "image/png");
            template.getRegions().add(FreeProjectTemplateRegion.builder()
                    .template(template)
                    .label(region.getLabel())
                    .category(region.getCategory())
                    .maskStorageKey(maskKey)
                    .appliedHexCode(region.getAppliedHexCode())
                    .appliedShadeCode(region.getAppliedShadeCode())
                    .displayOrder(order++)
                    .build());
        }

        FreeProjectTemplate saved = templateRepository.save(template);
        auditService.record(adminUserId, "FREE_TEMPLATE_PUBLISHED", "FREE_PROJECT_TEMPLATE", saved.getId(),
                "slug=" + slug + " space=" + space + " room=" + saved.getRoomKey()
                        + " walls=" + regions.size() + " from=" + project.getId());
        log.info("[library] published template {} ({} walls) from project {} by {}",
                slug, regions.size(), project.getId(), adminUserId);
        return toResponse(saved);
    }

    /**
     * Re-freeze a template from the project it was published from.
     *
     * Publishing copies the masks, which is what makes a template independent of the
     * project behind it — and also what made a published room permanently
     * un-editable. There was no way to widen a wall, fix an edge the AI overshot, or
     * add a surface that had been missed: the only route was delete and publish
     * again, which loses the slug (so every link to it), the display order, the
     * usage count and the shelf position. This is that route without the loss. The
     * studio stays the one place masks are drawn; this pushes the result out.
     *
     * What is kept: id, slug, title, room, space, description, display order,
     * published state and usage count. What is replaced: the photo, the cleaned
     * photo, and every wall.
     *
     * Copies people have already started are deliberately NOT changed. They point at
     * the old keys, and {@code storageService.store} mints a fresh key for every
     * write, so the new files land beside the old ones rather than over them —
     * somebody halfway through painting a room does not have its walls move under
     * them mid-session. The superseded files are then deleted only when nothing is
     * holding them, the same rule {@link #deleteTemplate} purges under.
     */
    @Transactional
    public FreeProjectTemplateResponse refreshFromSource(String adminUserId, String templateId) {
        FreeProjectTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found: " + templateId));
        if (template.getSourceProjectId() == null || template.getSourceProjectId().isBlank()) {
            throw new IllegalArgumentException(
                    "This one was published before we started recording which project it came from, "
                    + "so there is nothing to refresh it against. Publish it again from the studio.");
        }
        Project project = projectRepository
                .findByIdAndUserId(template.getSourceProjectId(), adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The project this was published from is gone, or belongs to another account. "
                        + "Publish a new one from the studio instead."));

        List<Region> regions = regionRepository.findByProjectIdOrderByDisplayOrderAsc(project.getId())
                .stream().filter(r -> hasMask(r.getMaskUrl())).toList();
        if (regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "That project has no walls on it any more, so refreshing would empty the room. "
                    + "Mark its walls in the studio first.");
        }

        // Held so they can be cleaned up after the swap — never before it, because a
        // failure anywhere below has to leave the template serving its old files.
        List<String> superseded = new ArrayList<>();
        superseded.add(template.getImageStorageKey());
        if (template.getCleanedImageStorageKey() != null) superseded.add(template.getCleanedImageStorageKey());
        template.getRegions().forEach(r -> superseded.add(r.getMaskStorageKey()));
        String oldImageKey = template.getImageStorageKey();

        String folder = FreeProjectStorage.folderFor(template.getSlug());
        UploadedImage sourceImage = project.getImage();
        byte[] photo = read(sourceImage.getStorageKey(), "photo");
        String imageKey = write(photo, folder, "source" + extensionOf(sourceImage.getOriginalFilename(), ".jpg"),
                sourceImage.getContentType());

        String cleanedKey = null;
        if (project.getCleanedImageStorageKey() != null && !project.getCleanedImageStorageKey().isBlank()) {
            cleanedKey = write(read(project.getCleanedImageStorageKey(), "cleaned photo"),
                    folder, "cleaned.png", "image/png");
        }

        Integer width = sourceImage.getWidth();
        Integer height = sourceImage.getHeight();
        if (width == null || height == null) {
            int[] wh = dimensionsOf(photo);
            if (wh != null) {
                width = wh[0];
                height = wh[1];
            }
        }

        template.setImageStorageKey(imageKey);
        template.setImageContentType(sourceImage.getContentType());
        template.setImageFileSize((long) photo.length);
        template.setImageWidth(width);
        template.setImageHeight(height);
        if (sourceImage.getImageType() != null) template.setImageType(sourceImage.getImageType());
        template.setCleanedImageStorageKey(cleanedKey);

        // orphanRemoval drops the old rows; the files they named are handled below.
        template.getRegions().clear();
        int order = 0;
        for (Region region : regions) {
            byte[] mask = read(storageKeyOf(region.getMaskUrl()), "mask for region " + region.getId());
            String maskKey = write(mask, folder, "mask-" + order + ".png", "image/png");
            template.getRegions().add(FreeProjectTemplateRegion.builder()
                    .template(template)
                    .label(region.getLabel())
                    .category(region.getCategory())
                    .maskStorageKey(maskKey)
                    .appliedHexCode(region.getAppliedHexCode())
                    .appliedShadeCode(region.getAppliedShadeCode())
                    .displayOrder(order++)
                    .build());
        }

        FreeProjectTemplate saved = templateRepository.save(template);

        // Only once nothing is holding the old room. A copy still open on it keeps
        // every one of these files alive, stale or not.
        long stillInUse = imageRepository.countByStorageKey(oldImageKey);
        int purged = stillInUse == 0 ? purge(superseded) : 0;

        auditService.record(adminUserId, "FREE_TEMPLATE_REFRESHED", "FREE_PROJECT_TEMPLATE", saved.getId(),
                "slug=" + saved.getSlug() + " walls=" + regions.size() + " from=" + project.getId()
                        + " oldFilesPurged=" + purged + " copiesOnOldFiles=" + stillInUse);
        log.info("[library] template {} refreshed from project {} by {} ({} walls, {} old file(s) purged, "
                        + "{} copy/copies left on the previous version)",
                saved.getSlug(), project.getId(), adminUserId, regions.size(), purged, stillInUse);
        return toResponse(saved);
    }

    // ─── Starting a copy ─────────────────────────────────────────────────────

    /**
     * Give {@code userId} their own copy of a template, named by its internal id.
     *
     * The admin console's route in — it lists the whole shelf, hidden rooms and all,
     * so it holds ids rather than slugs. Visitors come through
     * {@link #startFromPublishedSlug} instead.
     */
    @Transactional
    public StartedProjectResponse startFromTemplate(String userId, String templateId) {
        return startCopy(userId, templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found: " + templateId)));
    }

    /**
     * The same copy, reached the way a visitor reaches it: by the slug already on
     * the gallery card, with no internal id to hold.
     *
     * A hidden room reads as ABSENT rather than as refused, matching
     * {@link #getPublished} — whether an unpublished draft exists behind a guessed
     * slug is not a public fact, and this endpoint is reachable by anyone with an
     * account. The published filter therefore sits in the lookup, before
     * {@link #startCopy} is ever reached.
     */
    @Transactional
    public StartedProjectResponse startFromPublishedSlug(String userId, String slug) {
        return startCopy(userId, templateRepository.findBySlug(slug)
                .filter(FreeProjectTemplate::isPublished)
                .orElseThrow(() -> new ResourceNotFoundException("No published room: " + slug)));
    }

    /**
     * The copy itself.
     *
     * Rows only. The new image row and every new region row point at the template's
     * storage keys, which is what keeps this O(walls) inserts instead of a photo
     * upload plus a segmentation run. The project is born SEGMENTED because its
     * walls are already there — there is nothing for the pipeline to do.
     *
     * Free by construction: no entitlement is claimed, no plan credit reserved and
     * no points spent, because nothing expensive happened. That is what makes it
     * safe to offer to every signed-in visitor and not only to an admin.
     */
    private StartedProjectResponse startCopy(String userId, FreeProjectTemplate template) {
        if (!template.isPublished()) {
            throw new IllegalArgumentException("That template is not published yet.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Shares the template's storage key on purpose — this row names the file, it
        // does not own it. Deleting the project deletes this row and leaves the file.
        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(user)
                .originalFilename(template.getSlug() + extensionOf(template.getImageStorageKey(), ".jpg"))
                .storageKey(template.getImageStorageKey())
                .contentType(template.getImageContentType())
                .fileSize(template.getImageFileSize())
                .width(template.getImageWidth())
                .height(template.getImageHeight())
                .imageType(template.getImageType())
                .build());

        Project project = projectRepository.save(Project.builder()
                .user(user)
                .image(image)
                .name(template.getTitle())
                .roomType(template.getRoomLabel())
                .status(ProjectStatus.SEGMENTED)
                .cleanedImageStorageKey(template.getCleanedImageStorageKey())
                .build());

        List<Region> copies = new ArrayList<>();
        for (FreeProjectTemplateRegion tr : template.getRegions()) {
            copies.add(Region.builder()
                    .project(project)
                    .label(tr.getLabel())
                    .category(tr.getCategory())
                    // Both columns carry the key, matching how the pipeline writes its
                    // own regions; the read path presigns whichever it finds.
                    .maskUrl(tr.getMaskStorageKey())
                    .maskData(tr.getMaskStorageKey())
                    .appliedHexCode(tr.getAppliedHexCode())
                    .appliedShadeCode(tr.getAppliedShadeCode())
                    .displayOrder(tr.getDisplayOrder())
                    .manual(false)
                    .build());
        }
        regionRepository.saveAll(copies);
        templateRepository.incrementTimesUsed(template.getId());

        auditService.record(userId, "FREE_TEMPLATE_STARTED", "PROJECT", project.getId(),
                "template=" + template.getSlug() + " walls=" + copies.size());
        log.info("[library] project {} started from template {} by {} ({} walls, no AI, no credit)",
                project.getId(), template.getSlug(), userId, copies.size());

        return StartedProjectResponse.builder()
                .projectId(project.getId())
                .name(project.getName())
                .status(project.getStatus().name())
                .regionCount(copies.size())
                .templateId(template.getId())
                .templateTitle(template.getTitle())
                .build();
    }

    // ─── Editing the shelf ───────────────────────────────────────────────────

    /** Flip a template between listed and hidden without touching its files. */
    @Transactional
    public FreeProjectTemplateResponse setPublished(String adminUserId, String templateId, boolean published) {
        FreeProjectTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found: " + templateId));
        template.setPublished(published);
        auditService.record(adminUserId, published ? "FREE_TEMPLATE_PUBLISHED" : "FREE_TEMPLATE_HIDDEN",
                "FREE_PROJECT_TEMPLATE", templateId, "slug=" + template.getSlug());
        return toResponse(templateRepository.save(template));
    }

    /**
     * Take a template off the shelf.
     *
     * {@code purgeFiles} defaults to false, and that default is the important part:
     * copies already in people's accounts point at these exact files, so deleting
     * the blobs would blank out rooms that are open and being worked on. Dropping
     * the rows alone stops anyone starting a new copy while leaving every existing
     * one intact. Purge only once you know nobody is holding a copy — this is the
     * single place in the codebase permitted to delete library files.
     */
    @Transactional
    public TemplateDeletionResponse.Removed deleteTemplate(String adminUserId, String templateId, boolean purgeFiles) {
        FreeProjectTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found: " + templateId));

        // Counted BEFORE the rows go, so the audit line records how many rooms this
        // actually cost rather than how many were left afterwards.
        long copies = purgeFiles ? imageRepository.countByStorageKey(template.getImageStorageKey()) : 0L;

        List<String> keys = new ArrayList<>();
        if (purgeFiles) {
            keys.add(template.getImageStorageKey());
            if (template.getCleanedImageStorageKey() != null) keys.add(template.getCleanedImageStorageKey());
            template.getRegions().forEach(r -> keys.add(r.getMaskStorageKey()));
        }

        templateRepository.delete(template);

        int purged = purge(keys);

        auditService.record(adminUserId, "FREE_TEMPLATE_DELETED", "FREE_PROJECT_TEMPLATE", templateId,
                "slug=" + template.getSlug() + " filesPurged=" + purged + " copiesBroken=" + copies);
        if (purged > 0) {
            log.warn("[library] template {} deleted by {} — {} shared file(s) purged, {} live copy/copies "
                            + "now point at nothing", template.getSlug(), adminUserId, purged, copies);
        } else {
            log.info("[library] template {} taken off the shelf by {}; files kept",
                    template.getSlug(), adminUserId);
        }

        return TemplateDeletionResponse.Removed.builder()
                .id(templateId)
                .title(template.getTitle())
                .filesPurged(purged)
                .copiesBroken(copies)
                .build();
    }

    /**
     * Delete one or many, reporting each outcome separately.
     *
     * Each template is its own transaction (through the self-injected proxy), so one
     * that has already been deleted in another tab fails alone instead of rolling
     * back the rest of the selection — a bulk delete that silently did nothing
     * because of one stale id is worse than a partial one that says so.
     */
    public TemplateDeletionResponse deleteTemplates(String adminUserId, List<String> templateIds, boolean purgeFiles) {
        List<TemplateDeletionResponse.Removed> removed = new ArrayList<>();
        List<TemplateDeletionResponse.Failure> failed = new ArrayList<>();
        for (String id : templateIds) {
            try {
                removed.add(self.deleteTemplate(adminUserId, id, purgeFiles));
            } catch (ResourceNotFoundException e) {
                failed.add(TemplateDeletionResponse.Failure.builder()
                        .id(id).reason("Already gone.").build());
            } catch (RuntimeException e) {
                log.warn("[library] bulk delete failed for {}: {}", id, e.getMessage());
                failed.add(TemplateDeletionResponse.Failure.builder()
                        .id(id).reason("Could not be removed.").build());
            }
        }
        return TemplateDeletionResponse.builder()
                .removed(removed)
                .failed(failed)
                .filesPurged(removed.stream().mapToInt(TemplateDeletionResponse.Removed::getFilesPurged).sum())
                .copiesBroken(removed.stream().mapToLong(TemplateDeletionResponse.Removed::getCopiesBroken).sum())
                .build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Single-template variant — counts this one template's copies on its own. */
    private FreeProjectTemplateResponse toResponse(FreeProjectTemplate t) {
        return toResponse(t, imageRepository.countByStorageKey(t.getImageStorageKey()));
    }

    private FreeProjectTemplateResponse toResponse(FreeProjectTemplate t, long copiesInUse) {
        List<FreeProjectTemplateRegionResponse> regions = t.getRegions().stream()
                .map(r -> FreeProjectTemplateRegionResponse.from(r,
                        storageService.getPublicUrl(r.getMaskStorageKey())))
                .toList();
        // The cleaned photo is what the masks line up with, so it is the one to show
        // when there is one — the same choice the studio makes.
        String key = t.getCleanedImageStorageKey() != null && !t.getCleanedImageStorageKey().isBlank()
                ? t.getCleanedImageStorageKey()
                : t.getImageStorageKey();
        return FreeProjectTemplateResponse.from(t, storageService.getPublicUrl(key), regions, copiesInUse);
    }

    private byte[] read(String key, String what) {
        try {
            return storageService.load(key);
        } catch (IOException | RuntimeException e) {
            throw new StorageException("Could not read the " + what + " for this project", e);
        }
    }

    private String write(byte[] bytes, String folder, String filename, String contentType) {
        try {
            return storageService.store(bytes, folder, filename, contentType);
        } catch (IOException | RuntimeException e) {
            throw new StorageException("Could not copy " + filename + " into the free library", e);
        }
    }

    /**
     * Delete library files, reporting how many actually went.
     *
     * Best-effort per key: the rows that named them are already gone (or already
     * point elsewhere), and a stray blob is a smaller problem than an error that
     * invites a second delete against a template that is no longer there.
     */
    private int purge(List<String> keys) {
        int purged = 0;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            try {
                storageService.delete(key);
                purged++;
            } catch (Exception e) {
                log.warn("[library] could not delete {}: {}", key, e.getMessage());
            }
        }
        return purged;
    }

    private static boolean hasMask(String maskUrl) {
        return maskUrl != null && !maskUrl.isBlank();
    }

    /** Region mask columns hold a bare key on new rows and a URL on old ones. */
    private static String storageKeyOf(String urlOrKey) {
        try {
            String path = URI.create(urlOrKey).getRawPath();
            if (path == null) return urlOrKey;
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (IllegalArgumentException e) {
            return urlOrKey;
        }
    }

    private static int[] dimensionsOf(byte[] image) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));
            return decoded == null ? null : new int[]{decoded.getWidth(), decoded.getHeight()};
        } catch (IOException e) {
            return null; // dimensions are a nicety, not a reason to fail a publish
        }
    }

    private static String extensionOf(String nameOrKey, String fallback) {
        if (nameOrKey == null) return fallback;
        int dot = nameOrKey.lastIndexOf('.');
        if (dot < 0 || dot == nameOrKey.length() - 1) return fallback;
        String ext = nameOrKey.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("^\\.[a-z0-9]{1,5}$") ? ext : fallback;
    }

    private static TemplateSpace parseSpace(String raw) {
        try {
            return TemplateSpace.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Space must be INTERIOR or EXTERIOR");
        }
    }

    private static String resolveRoomLabel(PublishTemplateRequest request) {
        if (request.getRoomLabel() != null && !request.getRoomLabel().isBlank()) {
            return request.getRoomLabel().trim();
        }
        // "LIVING_ROOM" → "Living room"
        String words = request.getRoomKey().trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        return words.isEmpty() ? "Other" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Longest slug we build. uniqueSlug may add "-<n>" on top, inside the 120-char column. */
    private static final int MAX_SLUG_LENGTH = 100;

    /**
     * Lowercase, hyphenated, [a-z0-9-] only — it becomes a storage folder.
     *
     * One pass, no regex. The obvious version trimmed its edges with
     * {@code replaceAll("(^-+)|(-+$)", "")}, and an anchored {@code -+$} is the
     * textbook quadratic backtrack: given a title that is a long run of hyphens,
     * the engine retries the run from every position looking for an end that
     * isn't there. Emitting a separator only once a kept character follows it
     * gets the same result — runs collapsed, no leading or trailing hyphen —
     * in linear time, and breaking straight after appending an alphanumeric
     * means the length cap can never leave a hyphen dangling either.
     */
    static String slugify(String title) {
        if (title == null) return "room";
        StringBuilder slug = new StringBuilder();
        boolean separatorPending = false;
        for (int i = 0; i < title.length(); i++) {
            char c = Character.toLowerCase(title.charAt(i));
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9')) {
                separatorPending = true;
                continue;
            }
            if (separatorPending && slug.length() > 0) slug.append('-');
            separatorPending = false;
            slug.append(c);
            if (slug.length() >= MAX_SLUG_LENGTH) break;
        }
        return slug.length() == 0 ? "room" : slug.toString();
    }

    private String uniqueSlug(String candidate) {
        String base = slugify(candidate);
        if (!templateRepository.existsBySlug(base)) return base;
        for (int i = 2; i < 500; i++) {
            String next = base + "-" + i;
            if (!templateRepository.existsBySlug(next)) return next;
        }
        throw new IllegalArgumentException("Too many templates share that name — give this one a different title.");
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
