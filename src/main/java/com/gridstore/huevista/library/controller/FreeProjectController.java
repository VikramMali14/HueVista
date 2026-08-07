package com.gridstore.huevista.library.controller;

import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.library.FreeProjectStorage;
import com.gridstore.huevista.library.dto.PublicFreeProjectResponse;
import com.gridstore.huevista.library.service.FreeProjectLibraryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * The public gallery: rooms an admin has published, as anyone may read them.
 *
 * Read-only and published-only. Everything that changes the shelf — publishing,
 * refreshing, hiding, deleting, and starting a copy — stays on
 * {@code /api/admin/free-projects}, which SecurityConfig restricts to ROLE_ADMIN.
 * So "hide" in the admin console is what takes a room off the marketing site, and
 * there is no parameter here that could be made to return the hidden shelf.
 *
 * Not cached. The listing is small, it changes the moment an admin publishes or
 * hides something, and in S3 mode every image URL is presigned with an expiry —
 * a cached response would hand out links that are dead before the page is next
 * loaded. The frontend caches the rendered page instead, where a revalidation
 * window can be chosen with the presign lifetime in view.
 */
@RestController
@RequestMapping("/api/free-projects")
@RequiredArgsConstructor
@Tag(name = "Gallery", description = "Published rooms — public, read-only")
public class FreeProjectController {

    private final FreeProjectLibraryService libraryService;
    private final StorageService storageService;

    @Operation(summary = "List the published rooms",
            description = "Every room currently on the shelf, in gallery order. Hidden ones are never included.")
    @ApiResponse(responseCode = "200", description = "The gallery")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<List<PublicFreeProjectResponse>> list() {
        return ResponseEntity.ok(libraryService.listPublished());
    }

    @Operation(summary = "Get one published room",
            description = "By slug. A room that is hidden reads as absent rather than as forbidden — "
                    + "whether an unpublished draft exists is not a public fact.")
    @ApiResponse(responseCode = "200", description = "The room")
    @ApiResponse(responseCode = "404", description = "No such published room")
    @SecurityRequirements
    @GetMapping("/{slug}")
    public ResponseEntity<PublicFreeProjectResponse> get(
            @Parameter(description = "The room's slug, e.g. spice-market") @PathVariable String slug) {
        return ResponseEntity.ok(libraryService.getPublished(slug));
    }

    /**
     * The gallery's photographs, for browsers with no session.
     *
     * Only reachable in local-storage mode: with S3 configured the listing hands out
     * presigned links straight to the bucket and nothing comes here. Locally,
     * {@code getPublicUrl} produces {@code /api/images/files/<key>}, which requires
     * authentication — so without this route every picture on the public gallery
     * would 403 for exactly the visitors the page is for.
     *
     * Narrow on purpose. It serves keys under {@code free-projects/} and refuses
     * everything else, so it cannot be walked onto a customer's room photo the way a
     * general "serve any key" route could: the library prefix is the whole allowance,
     * and these files are already public by intent — they are what the gallery shows.
     * Traversal payloads are rejected before the prefix is even considered, since
     * {@code free-projects/../<someone>/x.jpg} passes a naive prefix test.
     */
    @Operation(hidden = true)
    @SecurityRequirements
    @GetMapping("/files/**")
    public ResponseEntity<byte[]> serveFile(HttpServletRequest request) throws IOException {
        String key = extractStorageKey(request);
        if (key.isBlank()
                || key.contains("..")
                || key.contains("\\")
                || key.indexOf('\0') >= 0
                || key.startsWith("/")
                || !key.startsWith(FreeProjectStorage.PREFIX)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] data;
        try {
            data = storageService.load(key);
        } catch (IOException | RuntimeException missing) {
            // A key that names nothing is a 404, not a 500 — a template refreshed or
            // deleted under a cached page lands here and should read as "gone".
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentTypeOf(key)))
                .header("X-Content-Type-Options", "nosniff")
                // Immutable in practice: every write mints a fresh UUID key, so a URL
                // never changes what it points at. Refreshing a room produces new keys
                // and therefore new URLs, which is what lets this be cached hard.
                .header("Cache-Control", "public, max-age=86400, immutable")
                .body(data);
    }

    private static String extractStorageKey(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String prefix = "/api/free-projects/files/";
        int idx = uri.indexOf(prefix);
        return idx >= 0 ? uri.substring(idx + prefix.length()) : "";
    }

    private static String contentTypeOf(String key) {
        if (key.endsWith(".png")) return "image/png";
        if (key.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
