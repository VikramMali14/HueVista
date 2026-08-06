package com.gridstore.huevista.siteasset.controller;

import com.gridstore.huevista.siteasset.dto.SiteAssetResponse;
import com.gridstore.huevista.siteasset.model.SiteAsset;
import com.gridstore.huevista.siteasset.service.SiteAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * The public half of the site-asset feature: what the marketing pages read.
 *
 * Everything here is anonymous by design. The home page has no session, so if
 * these required auth the images simply could not appear — which is the whole
 * point of the feature. That makes this the only route in the system that hands
 * stored bytes to someone who has not signed in, so it is deliberately narrow:
 * it addresses files by SLOT, never by storage key, and the service refuses to
 * load anything outside the site-asset prefix. There is no parameter here that a
 * caller could use to name a file of their own choosing.
 */
@RestController
@RequestMapping("/api/site-assets")
@RequiredArgsConstructor
@Tag(name = "Site assets", description = "Publicly readable images for the marketing site")
public class SiteAssetController {

    private final SiteAssetService siteAssetService;

    @Operation(
            summary = "Every filled slot",
            description = """
                    The manifest the marketing pages read at render time. Slots with no
                    upload are simply absent — the page draws its built-in default for
                    those, so an empty response is the normal state of a fresh install
                    and never an error.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Filled slots, slot-ascending")
    @GetMapping
    public ResponseEntity<List<SiteAssetResponse>> manifest() {
        return ResponseEntity.ok()
                // Short, because a page rendered from this is what an admin stares at
                // straight after uploading; the FILES behind it are what get cached hard.
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(siteAssetService.list());
    }

    @Operation(summary = "The image in a slot", description = "Serves the bytes. 404 when the slot has no upload.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image bytes"),
            @ApiResponse(responseCode = "404", description = "Nothing uploaded for this slot", content = @Content)
    })
    @GetMapping("/{slot}/file")
    public ResponseEntity<byte[]> file(@PathVariable String slot) {
        SiteAsset asset = siteAssetService.find(slot).orElse(null);
        if (asset == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] data = siteAssetService.load(asset);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                // A year, because the URL carries ?v=<updated-at>: replacing the image
                // changes the URL, so nothing downstream ever has to guess whether its
                // copy is stale. Without the version parameter this would be far too long.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag(asset.getSlot() + "-" + asset.getFileSize() + "-"
                      + (asset.getUpdatedAt() == null ? "0" : asset.getUpdatedAt()))
                // These bytes are attacker-influenced (an admin uploads them) and are
                // served from the API origin, so the browser is told in every way
                // available not to interpret them as anything but a picture.
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(data);
    }
}
