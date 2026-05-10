package com.gridstore.huevista.image.controller;

import com.gridstore.huevista.image.dto.ImageResponse;
import com.gridstore.huevista.image.service.ImageService;
import com.gridstore.huevista.image.service.StorageService;
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
public class ImageController {

    private final ImageService imageService;
    private final StorageService storageService;

    /**
     * POST /api/images/upload
     * Validates → AI-classifies → stores → returns metadata.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageResponse> upload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetails userDetails) {
        ImageResponse response = imageService.upload(file, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/images/{imageId}
     * Returns metadata for one image owned by the authenticated user.
     */
    @GetMapping("/{imageId}")
    public ResponseEntity<ImageResponse> getImage(
            @PathVariable String imageId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(imageService.getImage(imageId, userDetails.getUsername()));
    }

    /**
     * GET /api/images
     * Lists all images uploaded by the authenticated user (newest first).
     */
    @GetMapping
    public ResponseEntity<List<ImageResponse>> listImages(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(imageService.listImages(userDetails.getUsername()));
    }

    /**
     * GET /api/images/files/{userId}/{filename}
     * Serves the raw image file. Only the owning user may access their own files.
     */
    @GetMapping("/files/**")
    public ResponseEntity<byte[]> serveFile(
            HttpServletRequest request,
            @AuthenticationPrincipal UserDetails userDetails) throws IOException {
        String storageKey = extractStorageKey(request);
        String userId = userDetails.getUsername();

        // Security: storageKey is always {userId}/{uuid}.ext — deny cross-user access
        if (!storageKey.startsWith(userId + "/")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        byte[] data = storageService.load(storageKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(detectContentType(storageKey)))
                .body(data);
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
