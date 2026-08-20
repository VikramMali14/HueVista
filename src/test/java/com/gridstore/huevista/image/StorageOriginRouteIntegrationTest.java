package com.gridstore.huevista.image;

import com.gridstore.huevista.image.service.ClaudeVisionService;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/images/storage} has to be reachable by a caller holding no session,
 * because the reader is the frontend's own server configuring its image passthrough
 * before any user is involved.
 *
 * <p>Two things are pinned. First, that it is genuinely anonymous — the rule sits among
 * a long list of permitAll matchers and a later blanket rule could swallow it without
 * anything else failing. Second, that {@code /api/images/storage} routes HERE and not
 * into {@code ImageController}'s {@code /api/images/&#123;imageId&#125;}, which shares the
 * prefix. Spring resolves the literal segment ahead of the template, but the cost of
 * being wrong is an anonymous request reaching a handler that dereferences the caller's
 * principal, so it is worth a test rather than a comment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:application-test.properties")
class StorageOriginRouteIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;
    @MockitoBean ClaudeVisionService claudeVisionService;

    @Autowired MockMvc mockMvc;

    @Test
    @DisplayName("answers an anonymous caller, and answers as the storage endpoint")
    void publicAndCorrectlyRouted() throws Exception {
        mockMvc.perform(get("/api/images/storage"))
                .andExpect(status().isOk())
                // The test profile configures no bucket, so this is the local-disk answer.
                .andExpect(jsonPath("$.provider").value("local"))
                .andExpect(jsonPath("$.bucket").doesNotExist());
    }

    @Test
    @DisplayName("the rest of /api/images is still behind a session")
    void siblingRoutesStayPrivate() throws Exception {
        mockMvc.perform(get("/api/images/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isUnauthorized());
    }
}
