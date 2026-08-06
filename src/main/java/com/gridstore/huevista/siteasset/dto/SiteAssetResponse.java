package com.gridstore.huevista.siteasset.dto;

import com.gridstore.huevista.siteasset.model.SiteAsset;

import java.time.LocalDateTime;

/**
 * One filled slot, as both the admin console and the public site read it.
 *
 * `url` is a path on this API, not a storage key: callers must never learn where
 * the bytes actually live, and the public route is the only way in.
 */
public record SiteAssetResponse(
        String slot,
        String url,
        String contentType,
        long fileSize,
        Integer width,
        Integer height,
        String originalFilename,
        LocalDateTime updatedAt) {

    public static SiteAssetResponse from(SiteAsset asset) {
        return new SiteAssetResponse(
                asset.getSlot(),
                publicUrl(asset.getSlot(), asset.getUpdatedAt()),
                asset.getContentType(),
                asset.getFileSize(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getOriginalFilename(),
                asset.getUpdatedAt());
    }

    /**
     * Addressed by SLOT rather than by storage key, so the URL for a position in
     * the design never changes when the picture in it does.
     *
     * The updated-at stamp rides along as {@code ?v=}. Without it the two
     * requirements here contradict each other: a stable URL is what lets the page
     * be written once, and a stable URL is also exactly what a cache will keep
     * serving after the picture behind it has been replaced. The stamp moves the
     * moment an admin uploads, so the new file is a different URL to every cache
     * between here and the visitor while remaining the same slot to us.
     */
    public static String publicUrl(String slot, LocalDateTime updatedAt) {
        String base = "/api/site-assets/" + slot + "/file";
        return updatedAt == null ? base : base + "?v=" + updatedAt.toEpochSecond(java.time.ZoneOffset.UTC);
    }
}
