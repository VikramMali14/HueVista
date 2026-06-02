package com.gridstore.huevista.paint;

import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ShadeControllerIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired BrandRepository brandRepository;
    @Autowired ShadeRepository shadeRepository;

    private Brand asianPaints;

    @BeforeEach
    void setUp() {
        // PaintLineSeeder (an ApplicationRunner) seeds "Asian Paints" at startup,
        // so reuse the existing row instead of inserting a duplicate.
        asianPaints = brandRepository.findBySlug("asian-paints")
                .orElseGet(() -> brandRepository.save(Brand.builder()
                        .name("Asian Paints")
                        .slug("asian-paints")
                        .build()));

        shadeRepository.save(Shade.builder()
                .brand(asianPaints)
                .shadeCode("0001")
                .name("Bone China")
                .hexCode("#F3EEE4")
                .shadeFamily("off whites")
                .colorTemperature("neutral")
                .tonality("light")
                .suitableRooms(List.of("all rooms"))
                .popularity(1)
                .lrv(new BigDecimal("88.40"))
                .rgbR(243).rgbG(238).rgbB(228)
                .build());

        shadeRepository.save(Shade.builder()
                .brand(asianPaints)
                .shadeCode("1402")
                .name("Terracotta")
                .hexCode("#A47148")
                .shadeFamily("earths")
                .colorTemperature("warm")
                .tonality("medium")
                .suitableRooms(List.of("living room"))
                .popularity(22)
                .lrv(new BigDecimal("28.50"))
                .rgbR(164).rgbG(113).rgbB(72)
                .build());

        shadeRepository.save(Shade.builder()
                .brand(asianPaints)
                .shadeCode("1618")
                .name("Olive Branch")
                .hexCode("#5B6C5B")
                .shadeFamily("greens")
                .colorTemperature("cool")
                .tonality("dark")
                .suitableRooms(List.of("study"))
                .popularity(32)
                .lrv(new BigDecimal("18.20"))
                .rgbR(91).rgbG(108).rgbB(91)
                .build());
    }

    @Test
    void listAllShades_returnsAll() throws Exception {
        mockMvc.perform(get("/api/shades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void listByBrand_returnsBrandShades() throws Exception {
        mockMvc.perform(get("/api/shades/asian-paints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].shadeCode").value("0001"));
    }

    @Test
    void filterByFamily_returnsOnlyMatchingShades() throws Exception {
        mockMvc.perform(get("/api/shades").param("family", "earths"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Terracotta"));
    }

    @Test
    void filterByTemperature_returnsOnlyMatchingShades() throws Exception {
        mockMvc.perform(get("/api/shades").param("temperature", "cool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Olive Branch"));
    }

    @Test
    void filterByTonality_returnsOnlyMatchingShades() throws Exception {
        mockMvc.perform(get("/api/shades").param("tonality", "light"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Bone China"));
    }

    @Test
    void searchByName_matchesPartial() throws Exception {
        mockMvc.perform(get("/api/shades").param("search", "Terra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].shadeCode").value("1402"));
    }

    @Test
    void searchByShadeCode_returnsExactMatch() throws Exception {
        mockMvc.perform(get("/api/shades").param("search", "1402"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listFamilies_returnsDistinct() throws Exception {
        mockMvc.perform(get("/api/shades/asian-paints/families"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void getSingleShade_returnsDetail() throws Exception {
        mockMvc.perform(get("/api/shades/asian-paints/1402"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Terracotta"))
                .andExpect(jsonPath("$.hexCode").value("#A47148"));
    }

    @Test
    void getSingleShade_unknownCode_returns404() throws Exception {
        mockMvc.perform(get("/api/shades/asian-paints/9999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSingleShade_unknownBrand_returns404() throws Exception {
        mockMvc.perform(get("/api/shades/unknown-brand/0001"))
                .andExpect(status().isNotFound());
    }
}
