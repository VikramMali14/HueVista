package com.gridstore.huevista.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin mask browser reaching rooms it does not own.
 *
 * Two owners are set up on purpose, because a HueVista room has two ways of belonging to
 * someone: a registered user's room, and a walk-in's room that hangs off the access code
 * a shop issued and has no user account behind it at all. The reports worth chasing come
 * from both, so both have to be reachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class AdminProjectControllerIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired CustomerAccessCodeRepository accessCodeRepository;

    private String adminToken;
    private String retailerToken;
    private String retailerRoomId;
    private String walkInRoomId;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.save(User.builder()
                .name("Platform Admin")
                .email("mask-admin@huevista.test")
                .password(passwordEncoder.encode("admin-pass"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN)
                .emailVerified(true)
                .build());
        adminToken = login("mask-admin@huevista.test", "admin-pass");

        User retailer = userRepository.save(User.builder()
                .name("Asha Paints")
                .email("asha@shop.test")
                .password(passwordEncoder.encode("shop-pass"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.RETAILER)
                .emailVerified(true)
                .build());
        retailerToken = login("asha@shop.test", "shop-pass");

        retailerRoomId = projectRepository.save(Project.builder()
                .user(retailer)
                .image(image(retailer))
                .name("Front elevation")
                .status(ProjectStatus.SEGMENTED)
                .maskMode("MANUAL")
                .build()).getId();

        Organization shop = organizationRepository.save(Organization.builder()
                .name("Asha Paints Kolhapur")
                .slug("asha-paints-kolhapur")
                .type(OrgType.RETAILER)
                .owner(retailer)
                .build());
        CustomerAccessCode code = accessCodeRepository.save(CustomerAccessCode.builder()
                .organization(shop)
                .code("WALKIN01")
                .customerName("Sunita")
                .expiresAt(java.time.LocalDateTime.now().plusDays(10))
                .build());
        walkInRoomId = projectRepository.save(Project.builder()
                .accessCode(code)
                .image(image(retailer))
                .name("Sunita's living room")
                .status(ProjectStatus.SEGMENTED)
                .build()).getId();
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                AuthResponse.class).getAccessToken();
    }

    private UploadedImage image(User owner) {
        return imageRepository.save(UploadedImage.builder()
                .user(owner)
                .originalFilename("room.jpg")
                .storageKey("test/room-" + java.util.UUID.randomUUID() + ".jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.OUTDOOR)
                .build());
    }

    // ─── Access ──────────────────────────────────────────────────────────────

    @Test
    void isClosedToAnyoneWhoIsNotAnAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/projects"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/projects").header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/projects/" + retailerRoomId)
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isForbidden());
    }

    // ─── Listing ─────────────────────────────────────────────────────────────

    @Test
    void listsRoomsTheAdminDoesNotOwn() throws Exception {
        mockMvc.perform(get("/api/admin/projects").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + retailerRoomId + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + walkInRoomId + "')]").exists());
    }

    @Test
    void saysWhoEachRoomBelongsTo() throws Exception {
        // Finding a reported room among everyone else's is the whole job, so a row that
        // does not identify its owner is not usable.
        mockMvc.perform(get("/api/admin/projects?q=Front elevation")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ownerEmail").value("asha@shop.test"))
                .andExpect(jsonPath("$[0].ownerRole").value("RETAILER"))
                .andExpect(jsonPath("$[0].maskMode").value("MANUAL"));
    }

    @Test
    void identifiesAWalkInRoomByItsShopAndCode() throws Exception {
        // No account exists behind this room — the shop and the code ARE the identity.
        mockMvc.perform(get("/api/admin/projects?q=Sunita")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(walkInRoomId))
                .andExpect(jsonPath("$[0].ownerEmail").doesNotExist())
                .andExpect(jsonPath("$[0].shopName").value("Asha Paints Kolhapur"))
                .andExpect(jsonPath("$[0].accessCode").value("WALKIN01"));
    }

    @Test
    void findsARoomByTheOwnersEmailOrTheShopsName() throws Exception {
        // An admin starts from whatever the report gave them, which is rarely the room name.
        mockMvc.perform(get("/api/admin/projects?q=asha@shop.test")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + retailerRoomId + "')]").exists());

        mockMvc.perform(get("/api/admin/projects?q=kolhapur")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + walkInRoomId + "')]").exists());
    }

    @Test
    void searchIsCaseInsensitiveAndAnEmptySearchReturnsEverything() throws Exception {
        mockMvc.perform(get("/api/admin/projects?q=FRONT ELEVATION")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(retailerRoomId));

        mockMvc.perform(get("/api/admin/projects?q=  ").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + retailerRoomId + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + walkInRoomId + "')]").exists());
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    @Test
    void opensSomebodyElsesRoomReadOnly() throws Exception {
        mockMvc.perform(get("/api/admin/projects/" + retailerRoomId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(retailerRoomId))
                .andExpect(jsonPath("$.name").value("Front elevation"))
                .andExpect(jsonPath("$.regions").isArray())
                // An admin is here to look, not to paint in someone else's room.
                .andExpect(jsonPath("$.readOnly").value(true));
    }

    @Test
    void opensAWalkInRoomThatHasNoOwningAccount() throws Exception {
        mockMvc.perform(get("/api/admin/projects/" + walkInRoomId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(walkInRoomId))
                .andExpect(jsonPath("$.readOnly").value(true));
    }

    @Test
    void answers404ForARoomThatDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/admin/projects/no-such-room")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
