package com.gridstore.huevista.siteasset;

import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.common.exception.ImageValidationException;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.siteasset.model.SiteAsset;
import com.gridstore.huevista.siteasset.repository.SiteAssetRepository;
import com.gridstore.huevista.siteasset.service.SiteAssetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The rules that keep the one publicly-readable upload route in the system safe,
 * and the ones that keep the marketing site from going blank.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SiteAssetServiceTest {

    @Mock SiteAssetRepository repository;
    @Mock StorageService storageService;
    @Mock AuditService auditService;
    @InjectMocks SiteAssetService service;

    /** A real, decodable 2x1 PNG — the validator sniffs magic bytes, not filenames. */
    private static MockMultipartFile png(String name) throws IOException {
        var img = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }

    /* ── slot ids ──────────────────────────────────────────────────────── */

    @ParameterizedTest
    @ValueSource(strings = {
            "../etc/passwd",              // traversal
            "home/compare/before",        // path separators
            "Home.Compare.Before",        // upper case would collide by folding
            "home..compare",              // empty segment
            "home.compare.",              // trailing separator
            ".home",                      // leading separator
            "home compare",               // whitespace
            "",
    })
    void rejectsSlotIdsThatAreNotPlainDottedNames(String slot) throws IOException {
        assertThatThrownBy(() -> service.put(slot, png("a.png"), "admin-1"))
                .isInstanceOf(ImageValidationException.class);
        verifyNoInteractions(storageService);
    }

    @Test
    void acceptsAnOrdinarySlotId() throws IOException {
        when(storageService.store(any(org.springframework.web.multipart.MultipartFile.class), anyString()))
                .thenReturn("site-assets/abc.png");
        when(repository.findById("home.compare.before")).thenReturn(Optional.empty());
        when(repository.save(any(SiteAsset.class))).thenAnswer(i -> i.getArgument(0));

        var res = service.put("home.compare.before", png("room.png"), "admin-1");

        assertThat(res.slot()).isEqualTo("home.compare.before");
        assertThat(res.url()).startsWith("/api/site-assets/home.compare.before/file");
        assertThat(res.width()).isEqualTo(2);
        assertThat(res.height()).isEqualTo(1);
    }

    /* ── what may be uploaded ──────────────────────────────────────────── */

    @Test
    void rejectsAFileWhoseBytesAreNotAnImageEvenWhenItClaimsToBeOne() {
        var lying = new MockMultipartFile("file", "x.png", "image/png", "<svg onload=alert(1)>".getBytes());
        assertThatThrownBy(() -> service.put("home.hero", lying, "admin-1"))
                .isInstanceOf(ImageValidationException.class);
        verifyNoInteractions(storageService);
    }

    @Test
    void rejectsSvgOutright() {
        // Served to anonymous visitors from the API origin, so a document format
        // that can carry script is not on the menu however it is declared.
        var svg = new MockMultipartFile("file", "logo.svg", "image/svg+xml",
                "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes());
        assertThatThrownBy(() -> service.put("home.hero", svg, "admin-1"))
                .isInstanceOf(ImageValidationException.class);
    }

    /* ── replacing and clearing ────────────────────────────────────────── */

    @Test
    void replacingASlotDeletesTheFileItDisplaced() throws IOException {
        var old = SiteAsset.builder().slot("home.hero").storageKey("site-assets/old.png")
                .contentType("image/png").fileSize(10).build();
        when(repository.findById("home.hero")).thenReturn(Optional.of(old));
        when(storageService.store(any(org.springframework.web.multipart.MultipartFile.class), anyString()))
                .thenReturn("site-assets/new.png");
        when(repository.save(any(SiteAsset.class))).thenAnswer(i -> i.getArgument(0));

        service.put("home.hero", png("new.png"), "admin-1");

        verify(storageService).delete("site-assets/old.png");
    }

    @Test
    void aStorageFailureLeavesTheSlotShowingWhatItShowedBefore() throws IOException {
        when(storageService.store(any(org.springframework.web.multipart.MultipartFile.class), anyString()))
                .thenThrow(new IOException("bucket unreachable"));

        assertThatThrownBy(() -> service.put("home.hero", png("new.png"), "admin-1"))
                .isInstanceOf(RuntimeException.class);

        // Nothing was repointed and nothing was deleted — the old image is still live.
        verify(repository, never()).save(any());
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void clearingAnEmptySlotIsNotAnError() {
        when(repository.findById("home.hero")).thenReturn(Optional.empty());
        service.clear("home.hero", "admin-1");
        verify(repository, never()).delete(any());
    }

    /* ── the public read path ──────────────────────────────────────────── */

    @Test
    void refusesToServeARowPointingOutsideTheSiteAssetPrefix() {
        // Defence in depth: if a row ever named someone's private upload, the public
        // route must not become a way to read it.
        var tampered = SiteAsset.builder().slot("home.hero")
                .storageKey("user-42/secret.jpg").contentType("image/jpeg").build();
        assertThatThrownBy(() -> service.load(tampered))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(storageService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "site-assets/../user-42/secret.jpg",
            "/site-assets/a.png",
            "site-assets\\a.png",
    })
    void traversalShapesAreNotSiteAssetKeys(String key) {
        assertThat(SiteAssetStorage.isSiteAssetKey(key)).isFalse();
    }

    @Test
    void anOrdinarySiteAssetKeyIsAccepted() {
        assertThat(SiteAssetStorage.isSiteAssetKey("site-assets/9f2c.png")).isTrue();
    }
}
