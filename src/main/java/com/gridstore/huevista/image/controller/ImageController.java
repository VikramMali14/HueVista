package com.gridstore.huevista.image.controller;

import com.gridstore.huevista.image.dto.ImageResponse;
import com.gridstore.huevista.image.service.ImageService;
import com.gridstore.huevista.image.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
@Tag(name = "Images", description = "Upload and manage room/exterior photos")
public class ImageController {

    private final ImageService imageService;
    private final StorageService storageService;

    @Operation(
            summary = "Upload and classify an image",
            description = """
                    Accepts JPEG, PNG, or WebP up to 10 MB.
                    - Validates file type and size
                    - Runs Claude Haiku Vision to classify as INDOOR or OUTDOOR
                    - Rejects invalid images (selfies, food, landscapes) with 422
                    - Stores original in S3/local and returns metadata
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Image uploaded and classified"),
            @ApiResponse(responseCode = "422", description = "Invalid image — not a room or building exterior",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Wrong file type or size exceeds 10 MB",
                    content = @Content)
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageResponse> upload(
            @Parameter(description = "Room or building photo (JPEG/PNG/WebP, max 10 MB)")
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetails userDetails) {
        ImageResponse response = imageService.upload(file, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get image metadata", description = "Returns metadata for a single image owned by the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image metadata"),
            @ApiResponse(responseCode = "404", description = "Image not found or not owned by user", content = @Content)
    })
    @GetMapping("/{imageId}")
    public ResponseEntity<ImageResponse> getImage(
            @PathVariable String imageId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(imageService.getImage(imageId, userDetails.getUsername()));
    }

    @Operation(summary = "List my images", description = "Lists images uploaded by the authenticated user, newest first. Paged; defaults return the newest 200.")
    @ApiResponse(responseCode = "200", description = "Image list")
    @GetMapping
    public ResponseEntity<List<ImageResponse>> listImages(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 200") @RequestParam(defaultValue = "200") int size) {
        return ResponseEntity.ok(imageService.listImages(userDetails.getUsername(), page, size));
    }

    @Operation(hidden = true)
    @GetMapping("/files/**")
    public ResponseEntity<byte[]> serveFile(
            HttpServletRequest request,
            @AuthenticationPrincipal UserDetails userDetails) throws IOException {
        String storageKey = extractStorageKey(request);
        String userId = userDetails.getUsername();

        // Access control + path-traversal guard. A naive `startsWith(userId + "/")`
        // check is bypassable with traversal sequences — e.g. the key
        // "<myId>/../<otherId>/<uuid>.jpg" both starts with "<myId>/" AND escapes to
        // another user's directory (or, with enough "../", outside the storage root
        // entirely). Reject any traversal / absolute / backslash / NUL payload, then
        // require the (now-clean) key to live under the caller's own prefix.
        if (!isOwnedKey(storageKey, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        byte[] data = storageService.load(storageKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(detectContentType(storageKey)))
                .header("X-Content-Type-Options", "nosniff")
                .body(data);
    }

    /**
     * True only when {@code storageKey} is a clean relative key owned by {@code userId}.
     * Storage keys are always of the form "{userId}/{uuid}.{ext}" — they never contain
     * "..", a leading slash, a backslash or a NUL byte — so anything that does is an
     * attempted traversal and is rejected.
     */
    static boolean isOwnedKey(String storageKey, String userId) {
        if (storageKey == null || storageKey.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        if (storageKey.contains("..")
                || storageKey.contains("\\")
                || storageKey.indexOf('\0') >= 0
                || storageKey.startsWith("/")) {
            return false;
        }
        return storageKey.startsWith(userId + "/");
    }

    private String extractStorageKey(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String prefix = "/api/images/files/";
        int idx = uri.indexOf(prefix);
        return idx >= 0 ? uri.substring(idx + prefix.length()) : "";
    }

    private String detectContentType(String storageKey) {
        if (storageKey.endsWith(".png"))  return "image/png";
        if (storageKey.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
