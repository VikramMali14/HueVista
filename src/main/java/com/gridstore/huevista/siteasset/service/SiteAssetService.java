package com.gridstore.huevista.siteasset.service;

import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.common.exception.ImageValidationException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.common.exception.StorageException;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.siteasset.SiteAssetStorage;
import com.gridstore.huevista.siteasset.dto.SiteAssetResponse;
import com.gridstore.huevista.siteasset.model.SiteAsset;
import com.gridstore.huevista.siteasset.repository.SiteAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The marketing site's editable images.
 *
 * Everything here exists so that changing the photograph on the home page is an
 * upload rather than a deploy. Three things make it different from the ordinary
 * image pipeline, and each one is a deliberate departure:
 *
 *  1. No Claude Vision classification. That step exists to keep selfies and
 *     food photos out of the wall-painting flow, and it rejects anything that is
 *     not a room — which is most of what belongs on a marketing page. Running it
 *     here would refuse a perfectly good hero image.
 *  2. The files are readable by anonymous visitors, because the pages that show
 *     them have no session.
 *  3. One row per slot. Replacing an image overwrites the row and deletes the
 *     file it displaced, so this table never grows past the number of positions
 *     in the design.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteAssetService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    /**
     * Smaller than the 10 MB the room-photo pipeline takes. These are shipped to
     * every visitor of the home page rather than processed once for one shop, so
     * the ceiling is about what is reasonable to serve, not what is reasonable to
     * store. SVG is not in {@link #ALLOWED_TYPES} on purpose: an SVG is a document
     * that can carry script, and these are the one upload in the system served to
     * people who are not signed in.
     */
    private static final long MAX_SIZE_BYTES = 8L * 1024 * 1024;

    /**
     * A slot id becomes a URL path segment and the row's primary key, so it is
     * held to a shape that cannot traverse, cannot need escaping, and cannot
     * collide by case: lower-case alphanumerics separated by dots or hyphens.
     */
    private static final Pattern SLOT = Pattern.compile("[a-z0-9]+([.-][a-z0-9]+)*");
    private static final int SLOT_MAX = 120;

    private final SiteAssetRepository repository;
    private final StorageService storageService;
    private final AuditService auditService;

    /** Every filled slot, slot-ascending so the admin list has a stable order. */
    @Transactional(readOnly = true)
    public List<SiteAssetResponse> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(SiteAsset::getSlot))
                .map(SiteAssetResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<SiteAsset> find(String slot) {
        return repository.findById(requireValidSlot(slot));
    }

    /**
     * Put {@code file} in {@code slot}, replacing whatever was there.
     *
     * The new file is stored BEFORE the old one is deleted and the row is only
     * repointed once the bytes are safely down — a failed upload therefore leaves
     * the slot showing what it showed before, rather than emptying it. The
     * displaced file is deleted afterwards on a best-effort basis: a storage
     * hiccup there costs an orphaned object, which is a great deal cheaper than
     * failing an upload that has already succeeded.
     */
    @Transactional
    public SiteAssetResponse put(String slot, MultipartFile file, String adminUserId) {
        String key = requireValidSlot(slot);
        String contentType = validate(file);

        String storageKey;
        try {
            storageKey = storageService.store(file, SiteAssetStorage.FOLDER);
        } catch (IOException e) {
            throw new StorageException("Failed to store site asset", e);
        }

        SiteAsset existing = repository.findById(key).orElse(null);
        String displaced = existing != null ? existing.getStorageKey() : null;

        SiteAsset asset = existing != null ? existing : SiteAsset.builder().slot(key).build();
        asset.setStorageKey(storageKey);
        asset.setContentType(contentType);
        asset.setFileSize(file.getSize());
        asset.setOriginalFilename(trimFilename(file.getOriginalFilename()));
        asset.setUpdatedByUserId(adminUserId);
        readDimensions(file, asset);

        SiteAsset saved = repository.save(asset);

        if (displaced != null && !displaced.equals(storageKey)) {
            deleteQuietly(displaced);
        }

        auditService.record(adminUserId, "SITE_ASSET_UPDATED", "SITE_ASSET", key,
                "file=" + asset.getOriginalFilename() + " size=" + file.getSize() + "B");
        log.info("[site-asset] slot={} replaced by admin={} ({} bytes)", key, adminUserId, file.getSize());
        return SiteAssetResponse.from(saved);
    }

    /**
     * Empty a slot, which puts the front end's built-in default back on the page.
     *
     * Not an error when the slot is already empty — "make this slot show the
     * default" is the request, and it is satisfied either way.
     */
    @Transactional
    public void clear(String slot, String adminUserId) {
        String key = requireValidSlot(slot);
        repository.findById(key).ifPresent(asset -> {
            repository.delete(asset);
            deleteQuietly(asset.getStorageKey());
            auditService.record(adminUserId, "SITE_ASSET_CLEARED", "SITE_ASSET", key,
                    "slot returned to its built-in default");
            log.info("[site-asset] slot={} cleared by admin={}", key, adminUserId);
        });
    }

    /** The stored bytes for a filled slot, for the public file route. */
    @Transactional(readOnly = true)
    public byte[] load(SiteAsset asset) {
        // Belt and braces: the key was written by this service and can only be a
        // site-asset key, but the value that reaches storage is checked anyway
        // rather than trusted because it came out of our own table.
        if (!SiteAssetStorage.isSiteAssetKey(asset.getStorageKey())) {
            log.error("[site-asset] slot={} holds a key outside the site-asset prefix: {}",
                    asset.getSlot(), asset.getStorageKey());
            throw new ResourceNotFoundException("Site asset not available");
        }
        try {
            return storageService.load(asset.getStorageKey());
        } catch (IOException e) {
            throw new StorageException("Failed to read site asset", e);
        }
    }

    /* ── internals ─────────────────────────────────────────────────────── */

    private static String requireValidSlot(String slot) {
        String s = slot == null ? "" : slot.trim();
        if (s.isEmpty() || s.length() > SLOT_MAX || !SLOT.matcher(s).matches()) {
            throw new ImageValidationException(
                    "Slot must be a dotted lower-case name like \"home.compare.before\".");
        }
        return s;
    }

    private String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ImageValidationException("No file provided.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ImageValidationException("Image must not exceed 8 MB.");
        }
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new ImageValidationException("Only JPEG, PNG and WebP images are accepted.");
        }
        String sniffed;
        try {
            sniffed = sniff(file);
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded file", e);
        }
        // The declared type is a claim by the browser; the magic bytes are not.
        if (sniffed == null) {
            throw new ImageValidationException("That file is not a valid JPEG, PNG or WebP image.");
        }
        return sniffed;
    }

    /**
     * The MIME type the file's own header implies, or null if it is none of the
     * three we accept.
     *
     * Kept here rather than shared with the room-photo pipeline: the two allow
     * lists answer different questions ("can we repaint this?" vs "can we serve
     * this to the public?") and are free to diverge.
     */
    private static String sniff(MultipartFile file) throws IOException {
        byte[] h = new byte[12];
        int read;
        try (var in = file.getInputStream()) {
            read = in.readNBytes(h, 0, h.length);
        }
        if (read >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (read >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return "image/png";
        }
        if (read >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * Record the pixel dimensions if they can be read.
     *
     * Never fatal. The dimensions are a convenience — they let the admin page say
     * "this is 4:3 and the slot is drawn at 21:10" — and an image whose header
     * ImageIO cannot parse is still a perfectly serveable file.
     */
    private static void readDimensions(MultipartFile file, SiteAsset asset) {
        try (var in = file.getInputStream()) {
            BufferedImage img = ImageIO.read(in);
            if (img != null) {
                asset.setWidth(img.getWidth());
                asset.setHeight(img.getHeight());
                return;
            }
        } catch (IOException | RuntimeException e) {
            log.debug("[site-asset] could not read dimensions for slot={}: {}",
                    asset.getSlot(), e.getMessage());
        }
        asset.setWidth(null);
        asset.setHeight(null);
    }

    private static String trimFilename(String name) {
        if (name == null || name.isBlank()) return null;
        return name.length() <= 255 ? name : name.substring(0, 255);
    }

    private void deleteQuietly(String storageKey) {
        try {
            storageService.delete(storageKey);
        } catch (RuntimeException e) {
            log.warn("[site-asset] could not delete displaced file {} — orphaned: {}",
                    storageKey, e.getMessage());
        }
    }
}
