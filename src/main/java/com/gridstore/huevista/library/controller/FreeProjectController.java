package com.gridstore.huevista.library.controller;

import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.library.FreeProjectStorage;
import com.gridstore.huevista.library.dto.PublicFreeProjectResponse;
import com.gridstore.huevista.library.dto.StartedProjectResponse;
import com.gridstore.huevista.library.model.TemplatePlacement;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * The public gallery: rooms an admin has published, as anyone may read them —
 * plus the one write a visitor is allowed, which is taking a room away to paint.
 *
 * Published-only. Everything that changes the SHELF — publishing, refreshing,
 * hiding, deleting — stays on {@code /api/admin/free-projects}, which
 * SecurityConfig restricts to ROLE_ADMIN. So "hide" in the admin console is what
 * takes a room off the marketing site, and there is no parameter here that could
 * be made to return the hidden shelf.
 *
 * {@code POST /{slug}/start} is the exception, and it changes nothing on the shelf:
 * it gives the CALLER their own copy. It requires a session — SecurityConfig
 * permits only GET on this path, so the POST falls through to
 * {@code anyRequest().authenticated()} — and it is per-IP rate-limited, because a
 * copy is cheap to make but not free to store.
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
@Tag(name = "Gallery", description = "Published rooms — public to read, signed-in to paint")
public class FreeProjectController {

    private final FreeProjectLibraryService libraryService;
    private final StorageService storageService;

    @Operation(summary = "List the published rooms",
            description = """
                    Every room currently on the shelf, in gallery order. Hidden ones are never
                    included.

                    The library feeds two public pages, and `surface` picks which one you are
                    asking about: GALLERY for the /gallery grid, WORK for the /work portfolio.
                    A room filed under BOTH answers to either. Left off, the whole published
                    shelf comes back regardless of placement — which is what the signed-in
                    library wants, since a room is openable and paintable whichever marketing
                    page it happens to be filed under.
                    """)
    @ApiResponse(responseCode = "200", description = "The published rooms")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<List<PublicFreeProjectResponse>> list(
            @Parameter(description = "GALLERY or WORK. Omit for every published room.")
            @RequestParam(required = false) String surface) {
        return ResponseEntity.ok(libraryService.listPublished(TemplatePlacement.parse(surface, null)));
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

    @Operation(summary = "Paint this room",
            description = """
                    Opens the caller's own copy of a published room and returns its project id,
                    which the caller hands to the studio. Rows only: the copy points at the
                    photo and masks the library already stores, so nothing is uploaded, no mask
                    is generated, and no quota, plan credit or points are spent.

                    Requires a session — this is the one endpoint here that is not anonymous.
                    A hidden room answers 404 rather than 403, the same as reading one.
                    """)
    @ApiResponse(responseCode = "201", description = "The copy, ready for the studio")
    @ApiResponse(responseCode = "401", description = "No session")
    @ApiResponse(responseCode = "404", description = "No such published room")
    @PostMapping("/{slug}/start")
    public ResponseEntity<StartedProjectResponse> start(
            @Parameter(description = "The room's slug, e.g. spice-market") @PathVariable String slug,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(libraryService.startFromPublishedSlug(auth.getName(), slug));
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
