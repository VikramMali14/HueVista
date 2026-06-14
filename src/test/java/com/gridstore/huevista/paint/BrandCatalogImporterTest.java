package com.gridstore.huevista.paint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.paint.service.BrandCatalogImporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the simple {name, code, hex, category} catalogue importer:
 * de-duplication, malformed-hex handling, hex normalisation, category-merge
 * ordering across files, and idempotent re-runs.
 */
@ExtendWith(MockitoExtension.class)
class BrandCatalogImporterTest {

    @Mock BrandRepository brandRepository;
    @Mock ShadeRepository shadeRepository;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks BrandCatalogImporter importer;

    @Captor ArgumentCaptor<List<Shade>> savedCaptor;

    private final Brand brand = Brand.builder().id(1L).name("Test").slug("test-brand").build();

    @BeforeEach
    void stubBrand() {
        // Lenient: the "create brand when missing" test stubs a different slug instead.
        lenient().when(brandRepository.findBySlug("test-brand")).thenReturn(Optional.of(brand));
    }

    private List<Shade> capturedShades() {
        verify(shadeRepository).saveAll(savedCaptor.capture());
        return savedCaptor.getValue();
    }

    private Map<String, Shade> byCode(List<Shade> shades) {
        return shades.stream().collect(java.util.stream.Collectors.toMap(Shade::getShadeCode, s -> s));
    }

    @Test
    void skipsDuplicateAndBadHexRows_andNormalisesHex() {
        when(shadeRepository.findShadeCodesByBrandId(1L)).thenReturn(new ArrayList<>());

        int inserted = importer.importBrand("Test", "test-brand", List.of("seed/brands-test/array.json"));

        // R1 (first only), G1 — B1 bad hex, N1 missing hex, and the second R1 are all skipped.
        assertThat(inserted).isEqualTo(2);
        Map<String, Shade> shades = byCode(capturedShades());
        assertThat(shades.keySet()).containsExactlyInAnyOrder("R1", "G1");

        Shade r1 = shades.get("R1");
        assertThat(r1.getName()).isEqualTo("Red One"); // first wins over "Red Dup"
        assertThat(r1.getHexCode()).isEqualTo("#FF0000");
        assertThat(r1.getShadeFamily()).isEqualTo("Reds");
        assertThat(new int[]{r1.getRgbR(), r1.getRgbG(), r1.getRgbB()}).containsExactly(255, 0, 0);

        // Missing leading '#' is normalised and upper-cased.
        assertThat(shades.get("G1").getHexCode()).isEqualTo("#00AA00");
        assertThat(shades.get("G1").getShadeFamily()).isEqualTo("Greens");
    }

    @Test
    void mergesFilesInOrder_firstFileWinsCategory() {
        when(shadeRepository.findShadeCodesByBrandId(1L)).thenReturn(new ArrayList<>());

        int inserted = importer.importBrand("Test", "test-brand",
                List.of("seed/brands-test/object-first.json", "seed/brands-test/array-second.json"));

        // S1 from the first (categorised) file; the second file's S1 is a dupe and skipped; C1 is new.
        assertThat(inserted).isEqualTo(2);
        Map<String, Shade> shades = byCode(capturedShades());
        assertThat(shades.keySet()).containsExactlyInAnyOrder("S1", "C1");
        assertThat(shades.get("S1").getShadeFamily()).isEqualTo("Blues"); // first file wins
        assertThat(shades.get("S1").getName()).isEqualTo("Shared");
        assertThat(shades.get("C1").getShadeFamily()).isNull();           // uncategorised file
    }

    @Test
    void isIdempotent_whenAllCodesAlreadyExist() {
        when(shadeRepository.findShadeCodesByBrandId(1L))
                .thenReturn(new ArrayList<>(List.of("S1", "C1")));

        int inserted = importer.importBrand("Test", "test-brand",
                List.of("seed/brands-test/object-first.json", "seed/brands-test/array-second.json"));

        assertThat(inserted).isZero();
        verify(shadeRepository, never()).saveAll(any());
    }

    @Test
    void createsBrandWhenMissing() {
        when(brandRepository.findBySlug("new-brand")).thenReturn(Optional.empty());
        when(brandRepository.save(any(Brand.class))).thenReturn(brand);
        when(shadeRepository.findShadeCodesByBrandId(anyLong())).thenReturn(new ArrayList<>());

        int inserted = importer.importBrand("New Brand", "new-brand", List.of("seed/brands-test/array.json"));

        assertThat(inserted).isEqualTo(2);
        verify(brandRepository).save(any(Brand.class));
    }
}
