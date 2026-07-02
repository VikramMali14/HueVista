package com.gridstore.huevista.paint;

import com.gridstore.huevista.paint.dto.ShadeUploadItem;
import com.gridstore.huevista.paint.dto.ShadeUploadResponse;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.paint.service.ShadeEnrichmentService;
import com.gridstore.huevista.paint.service.ShadeUploadService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the public bulk-upload: a new company is created and valid shades are inserted
 * with computed colour maths, duplicates (in-file and already-present) are skipped, an
 * existing company is reused by slug, and bad rows are rejected with a clear message.
 */
class ShadeUploadServiceTest {

    private final BrandRepository brandRepo = mock(BrandRepository.class);
    private final ShadeRepository shadeRepo = mock(ShadeRepository.class);
    private final ShadeEnrichmentService enrichmentService = mock(ShadeEnrichmentService.class);
    private final ShadeUploadService service = new ShadeUploadService(brandRepo, shadeRepo, enrichmentService);

    private static ShadeUploadItem item(String code, String name, String hex) {
        ShadeUploadItem it = new ShadeUploadItem();
        it.setCode(code);
        it.setName(name);
        it.setHex(hex);
        return it;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<Shade>> captureSaveAll() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void createsNewCompanyAndInsertsValidShadesWithComputedFields() {
        when(brandRepo.findBySlug("berger")).thenReturn(Optional.empty());
        when(brandRepo.save(any(Brand.class))).thenAnswer(inv -> {
            Brand b = inv.getArgument(0);
            b.setId(2L);
            return b;
        });

        ShadeUploadResponse res = service.upload(null, "Berger",
                List.of(item("9436", "Air Breeze", "F3EDE8"),
                        item("3050", "Deep Ocean", "#274C66")),
                false);

        assertThat(res.getBrand()).isEqualTo("Berger");
        assertThat(res.getSlug()).isEqualTo("berger");
        assertThat(res.getInserted()).isEqualTo(2);
        assertThat(res.getSkipped()).isZero();
        assertThat(res.getTotal()).isEqualTo(2);

        ArgumentCaptor<List<Shade>> saved = captureSaveAll();
        verify(shadeRepo).saveAll(saved.capture());
        Shade first = saved.getValue().get(0);
        // hex normalised to upper-case with leading '#', and RGB split computed.
        assertThat(first.getHexCode()).isEqualTo("#F3EDE8");
        assertThat(first.getRgbR()).isEqualTo(0xF3);
        assertThat(first.getRgbG()).isEqualTo(0xED);
        assertThat(first.getRgbB()).isEqualTo(0xE8);
        assertThat(first.getLrv()).isNotNull();
        // enrich = false → Claude is never called.
        verifyNoInteractions(enrichmentService);
    }

    @Test
    void enrichesInsertedShadesWhenEnrichIsOn() {
        Brand brand = Brand.builder().id(1L).name("Asian Paints").slug("asian-paints").build();
        when(brandRepo.findBySlug("asian-paints")).thenReturn(Optional.of(brand));
        when(enrichmentService.enrichBatch(anyList())).thenReturn(List.of(
                new ShadeEnrichmentService.EnrichmentResult(
                        List.of("modern", "coastal"),
                        List.of("calm"),
                        List.of("matte"),
                        "A soft off-white that keeps a sunlit room airy.")));

        service.upload("asian-paints", null, List.of(item("9436", "Air Breeze", "#F3EDE8")), true);

        ArgumentCaptor<List<Shade>> saved = captureSaveAll();
        verify(shadeRepo).saveAll(saved.capture());
        Shade s = saved.getValue().get(0);
        assertThat(s.getStyleTags()).containsExactly("modern", "coastal");
        assertThat(s.getMoodDescriptors()).containsExactly("calm");
        assertThat(s.getFinishRecommendations()).containsExactly("matte");
        assertThat(s.getAiDescription()).contains("airy");
    }

    @Test
    void skipsDuplicateCodesInFileAndAlreadyPresentShades() {
        Brand brand = Brand.builder().id(1L).name("Asian Paints").slug("asian-paints").build();
        when(brandRepo.findBySlug("asian-paints")).thenReturn(Optional.of(brand));
        // "7112" already exists in the catalogue; "9436" appears twice in the file.
        when(shadeRepo.findShadeCodesByBrandId(1L)).thenReturn(List.of("7112"));

        ShadeUploadResponse res = service.upload("asian-paints", null,
                List.of(item("9436", "Air Breeze", "#F3EDE8"),
                        item("9436", "Air Breeze Dup", "#F3EDE8"),
                        item("7112", "Terracotta", "#C46A4B")),
                false);

        assertThat(res.getInserted()).isEqualTo(1);
        assertThat(res.getSkipped()).isEqualTo(2);
        assertThat(res.getTotal()).isEqualTo(3);
        // Existing brand reused — never created.
        verify(brandRepo, never()).save(any());
    }

    @Test
    void rejectsInvalidHexWithRowContext() {
        Brand brand = Brand.builder().id(1L).name("Asian Paints").slug("asian-paints").build();
        when(brandRepo.findBySlug("asian-paints")).thenReturn(Optional.of(brand));

        assertThatThrownBy(() -> service.upload("asian-paints", null,
                List.of(item("9436", "Air Breeze", "#F3EDE8"),
                        item("bad1", "Broken", "not-a-hex")),
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Row 2")
                .hasMessageContaining("hex");
        verify(shadeRepo, never()).saveAll(any());
    }

    @Test
    void rejectsMissingRequiredFields() {
        Brand brand = Brand.builder().id(1L).name("Asian Paints").slug("asian-paints").build();
        when(brandRepo.findBySlug("asian-paints")).thenReturn(Optional.of(brand));

        assertThatThrownBy(() -> service.upload("asian-paints", null,
                List.of(item("9436", null, "#F3EDE8")), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsEmptyArrayAndMissingCompany() {
        assertThatThrownBy(() -> service.upload("asian-paints", null, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");

        assertThatThrownBy(() -> service.upload(null, null,
                List.of(item("9436", "Air Breeze", "#F3EDE8")), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("company");
    }
}
