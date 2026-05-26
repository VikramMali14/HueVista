package com.gridstore.huevista.image;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.service.ClaudeVisionService;
import com.razorpay.RazorpayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ImageControllerIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;
    @MockitoBean ClaudeVisionService claudeVisionService;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.save(User.builder()
                .name("Image User")
                .email("image-user@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(false)
                .build());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"image-user@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        AuthResponse authResp = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), AuthResponse.class);
        accessToken = authResp.getAccessToken();
    }

    @Test
    void uploadValidImage_classifiesAndPersists() throws Exception {
        when(claudeVisionService.classify(any())).thenReturn(ImageType.INDOOR);

        MockMultipartFile file = new MockMultipartFile(
                "file", "room.jpg", "image/jpeg", fakeJpegBytes());

        mockMvc.perform(multipart("/api/images/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageId").isNotEmpty())
                .andExpect(jsonPath("$.imageType").value("INDOOR"))
                .andExpect(jsonPath("$.originalFilename").value("room.jpg"));
    }

    @Test
    void uploadWrongContentType_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.pdf", "application/pdf", "not an image".getBytes());

        mockMvc.perform(multipart("/api/images/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void uploadWithoutAuth_returns401or403() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "room.jpg", "image/jpeg", fakeJpegBytes());

        mockMvc.perform(multipart("/api/images/upload").file(file))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listImages_emptyForNewUser() throws Exception {
        mockMvc.perform(get("/api/images")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getNonExistentImage_returns404() throws Exception {
        mockMvc.perform(get("/api/images/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    /** Minimal JPEG header so file-type sniffing doesn't reject it. */
    private static byte[] fakeJpegBytes() {
        // SOI marker + JFIF APP0 + a small payload + EOI
        byte[] header = {
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
                0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
        };
        byte[] body = new byte[256];
        byte[] eoi = { (byte) 0xFF, (byte) 0xD9 };
        byte[] out = new byte[header.length + body.length + eoi.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(eoi, 0, out, header.length + body.length, eoi.length);
        return out;
    }
}
