package com.gridstore.huevista.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.library.model.TemplatePlacement;
import com.gridstore.huevista.library.repository.FreeProjectTemplateRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import com.razorpay.RazorpayClient;
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

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where a published room shows, and the copy printed beside it.
 *
 * The library used to feed one page. "Published" and "on the gallery" were the
 * same fact, and the portfolio at /work was a hand-written file of invented rooms
 * that nothing an admin did could reach. Now publishing asks WHICH page, and the
 * two are read by different people: the gallery is a grid to browse and paint
 * from, "Our work" is a portfolio where each room carries a story.
 *
 * The things worth pinning are the ones that would be silent if they broke. A
 * room must not appear on the page it was not filed under. A room published
 * before any of this existed must stay exactly where it was — a migration that
 * quietly moves somebody's shelf is worse than one that fails. And a PATCH that
 * omits a field must leave that field alone, because the alternative is an admin
 * fixing a typo in the story and wiping the location they never saw.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class FreeProjectPlacementIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired StorageService storageService;
    @Autowired FreeProjectTemplateRepository templateRepository;
    @Autowired PasswordEncoder passwordEncoder;

    // ── Fixtures ──────────────────────────────────────────────────────────

    private User admin() {
        return userRepository.save(User.builder()
                .name("Root Admin").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
    }

    private User customer() {
        return userRepository.save(User.builder()
                .name("Asha Rao").email("asha@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.CUSTOMER).build());
    }

    private String tokenFor(String email) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private Project projectWith(User owner, String name) throws IOException {
        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(owner)
                .originalFilename("room.jpg")
                .storageKey(storageService.store(new byte[]{1, 2, 3, 4}, owner.getId(), "room.jpg", "image/jpeg"))
                .contentType("image/jpeg")
                .fileSize(4L)
                .width(1600).height(1200)
                .imageType(ImageType.INDOOR)
                .build());
        Project project = projectRepository.save(Project.builder()
                .user(owner).image(image).name(name).roomType("Living room")
                .status(ProjectStatus.SEGMENTED).build());
        String mask = storageService.store(new byte[]{1, 2, 3, 4}, owner.getId(), "mask-0.png", "image/png");
        regionRepository.save(Region.builder()
                .project(project)
                .label("Main wall")
                .category(RegionCategory.MAIN_WALL)
                .maskUrl(mask)
                .maskData(mask)
                .appliedHexCode("#9D5236")
                .appliedShadeCode("HV-1410")
                .displayOrder(0)
                .manual(false)
                .build());
        return project;
    }

    /** Publish with an arbitrary JSON body, returning the created template. */
    private JsonNode publish(String token, String bodyJson) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/admin/free-projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode publishRoom(String token, Project project, String placement) throws Exception {
        String placementField = placement == null ? "" : ",\"placement\":\"%s\"".formatted(placement);
        return publish(token, """
                {"projectId":"%s","title":"%s","space":"INTERIOR","roomKey":"LIVING_ROOM"%s}"""
                .formatted(project.getId(), project.getName(), placementField));
    }

    /** One public page's worth of rooms, read with no session at all. */
    private JsonNode surface(String surface) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/free-projects").param("surface", surface))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private static java.util.List<String> titlesIn(JsonNode rooms) {
        return java.util.stream.StreamSupport.stream(rooms.spliterator(), false)
                .map(r -> r.path("title").asText())
                .toList();
    }

    // ── Which page a room lands on ────────────────────────────────────────

    /**
     * The default is the portfolio, because that is the destination the admin
     * console now opens on. A publish that says nothing about placement is an
     * admin using the form as it is presented to them.
     */
    @Test
    void a_room_published_without_a_placement_goes_to_our_work() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        publishRoom(token, projectWith(root, "Sunlit hall"), null);

        assertThat(titlesIn(surface("WORK"))).containsExactly("Sunlit hall");
        assertThat(titlesIn(surface("GALLERY"))).isEmpty();
    }

    @Test
    void a_room_filed_under_gallery_stays_off_the_portfolio() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        publishRoom(token, projectWith(root, "Grid room"), "GALLERY");

        assertThat(titlesIn(surface("GALLERY"))).containsExactly("Grid room");
        assertThat(titlesIn(surface("WORK"))).isEmpty();
    }

    @Test
    void a_room_filed_under_both_answers_to_either_page() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        publishRoom(token, projectWith(root, "Everywhere room"), "BOTH");

        assertThat(titlesIn(surface("GALLERY"))).containsExactly("Everywhere room");
        assertThat(titlesIn(surface("WORK"))).containsExactly("Everywhere room");
    }

    /**
     * The in-app library is not a marketing page. A room is openable and
     * paintable whichever page it is filed under, and gating that on an editorial
     * choice would take rooms away from signed-in accounts for a reason no
     * account could see.
     */
    @Test
    void asking_for_no_surface_returns_the_whole_published_shelf() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        publishRoom(token, projectWith(root, "On the grid"), "GALLERY");
        publishRoom(token, projectWith(root, "In the portfolio"), "WORK");

        MvcResult res = mockMvc.perform(get("/api/free-projects")).andExpect(status().isOk()).andReturn();
        assertThat(titlesIn(objectMapper.readTree(res.getResponse().getContentAsString())))
                .containsExactlyInAnyOrder("On the grid", "In the portfolio");
    }

    /** Hiding a room takes it off both pages — placement says where, not whether. */
    @Test
    void hiding_a_room_takes_it_off_the_page_it_was_filed_under() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        String id = publishRoom(token, projectWith(root, "Sunlit hall"), "WORK").path("id").asText();

        mockMvc.perform(patch("/api/admin/free-projects/{id}/published", id)
                        .header("Authorization", "Bearer " + token)
                        .param("published", "false"))
                .andExpect(status().isOk());

        assertThat(titlesIn(surface("WORK"))).isEmpty();
    }

    /**
     * A misspelled surface is refused rather than silently treated as "everything".
     * Quietly widening the query would put gallery rooms on the portfolio page,
     * which is precisely the mistake placement exists to prevent.
     */
    @Test
    void an_unknown_surface_is_rejected_rather_than_ignored() throws Exception {
        mockMvc.perform(get("/api/free-projects").param("surface", "our-work"))
                .andExpect(status().isBadRequest());
    }

    // ── Moving a room after the fact ──────────────────────────────────────

    @Test
    void an_admin_can_move_a_room_from_the_gallery_to_our_work() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        String id = publishRoom(token, projectWith(root, "Sunlit hall"), "GALLERY").path("id").asText();

        mockMvc.perform(patch("/api/admin/free-projects/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placement\":\"WORK\"}"))
                .andExpect(status().isOk());

        assertThat(titlesIn(surface("WORK"))).containsExactly("Sunlit hall");
        assertThat(titlesIn(surface("GALLERY"))).isEmpty();
    }

    @Test
    void editing_a_room_is_admin_only() throws Exception {
        User root = admin();
        String adminToken = tokenFor("root@example.com");
        String id = publishRoom(adminToken, projectWith(root, "Sunlit hall"), "WORK").path("id").asText();
        customer();

        mockMvc.perform(patch("/api/admin/free-projects/{id}", id)
                        .header("Authorization", "Bearer " + tokenFor("asha@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placement\":\"GALLERY\"}"))
                .andExpect(status().isForbidden());

        assertThat(templateRepository.findById(id).orElseThrow().getPlacement())
                .isEqualTo(TemplatePlacement.WORK);
    }

    /**
     * The null-versus-empty rule, which is the whole reason this is a PATCH of
     * boxed types. An admin editing one field must not lose the others.
     */
    @Test
    void a_field_left_out_of_the_patch_is_left_alone_and_an_empty_one_is_cleared() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        String id = publish(token, """
                {"projectId":"%s","title":"Sunlit hall","space":"INTERIOR","roomKey":"LIVING_ROOM",
                 "placement":"WORK","location":"Pune","credit":"Previewed at the counter"}"""
                .formatted(projectWith(root, "Sunlit hall").getId()))
                .path("id").asText();

        // Mentions neither location nor credit; clears the credit explicitly.
        mockMvc.perform(patch("/api/admin/free-projects/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blurb\":\"A west-facing room.\",\"credit\":\"\"}"))
                .andExpect(status().isOk());

        var saved = templateRepository.findById(id).orElseThrow();
        assertThat(saved.getLocation()).isEqualTo("Pune");   // never mentioned → kept
        assertThat(saved.getCredit()).isNull();              // sent empty → cleared
        assertThat(saved.getBlurb()).isEqualTo("A west-facing room.");
    }

    @Test
    void a_patch_cannot_blank_the_title() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        String id = publishRoom(token, projectWith(root, "Sunlit hall"), "WORK").path("id").asText();

        mockMvc.perform(patch("/api/admin/free-projects/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertThat(templateRepository.findById(id).orElseThrow().getTitle()).isEqualTo("Sunlit hall");
    }

    // ── The editorial copy ────────────────────────────────────────────────

    /**
     * Admins type the story and the stats into textareas, so they arrive with
     * whatever spacing the person used. Splitting happens once, on the public
     * response, rather than being re-derived (differently) by each page.
     */
    @Test
    void the_story_and_stats_reach_the_portfolio_as_paragraphs_and_pairs() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        publish(token, """
                {"projectId":"%s","title":"Sunlit hall","space":"INTERIOR","roomKey":"LIVING_ROOM",
                 "placement":"WORK","location":"Pune","projectYear":"2026",
                 "credit":"Previewed at the counter · Pune",
                 "blurb":"A west-facing room that asked for warmth.",
                 "story":"They came in with a phone photo.\\n\\n\\nThe rust held its depth.\\n",
                 "stats":"Surfaces: 4 walls\\nPhoto to preview: 18 s\\nDecided"}"""
                .formatted(projectWith(root, "Sunlit hall").getId()));

        JsonNode room = surface("WORK").get(0);
        assertThat(room.path("location").asText()).isEqualTo("Pune");
        assertThat(room.path("projectYear").asText()).isEqualTo("2026");
        assertThat(room.path("credit").asText()).isEqualTo("Previewed at the counter · Pune");
        assertThat(room.path("onWork").asBoolean()).isTrue();
        assertThat(room.path("onGallery").asBoolean()).isFalse();

        // Blank runs and the trailing newline collapse; no empty paragraph survives.
        assertThat(room.path("story")).hasSize(2);
        assertThat(room.path("story").get(1).asText()).isEqualTo("The rust held its depth.");

        // Split on the FIRST colon; a line without one is kept rather than dropped.
        assertThat(room.path("stats")).hasSize(3);
        assertThat(room.path("stats").get(1).path("label").asText()).isEqualTo("Photo to preview");
        assertThat(room.path("stats").get(1).path("value").asText()).isEqualTo("18 s");
        assertThat(room.path("stats").get(2).path("label").asText()).isEqualTo("Decided");
        assertThat(room.path("stats").get(2).path("value").asText()).isEmpty();
    }

    /**
     * A portfolio room with no editorial copy at all is a normal state, not a
     * broken one — the page omits what it has nothing for. What it must never do
     * is fail, or hand back nulls where the page expects a list.
     */
    @Test
    void a_room_with_no_editorial_copy_still_reads_cleanly() throws Exception {
        User root = admin();
        publishRoom(tokenFor("root@example.com"), projectWith(root, "Sunlit hall"), "WORK");

        JsonNode room = surface("WORK").get(0);
        assertThat(room.path("story").isArray()).isTrue();
        assertThat(room.path("story")).isEmpty();
        assertThat(room.path("stats").isArray()).isTrue();
        assertThat(room.path("stats")).isEmpty();
        // Still has everything the card can derive from the room itself.
        assertThat(room.path("colours").get(0).path("shadeCode").asText()).isEqualTo("HV-1410");
        assertThat(room.path("imageUrl").asText()).isNotBlank();
    }

    /**
     * The by-slug read carries the placement too, so a room's own page can refuse
     * one belonging to the other surface instead of rendering it under the wrong
     * heading.
     */
    @Test
    void a_rooms_own_url_says_which_page_it_belongs_to() throws Exception {
        User root = admin();
        String token = tokenFor("root@example.com");
        String slug = publishRoom(token, projectWith(root, "Grid room"), "GALLERY").path("slug").asText();

        MvcResult res = mockMvc.perform(get("/api/free-projects/{slug}", slug))
                .andExpect(status().isOk()).andReturn();
        JsonNode room = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(room.path("onGallery").asBoolean()).isTrue();
        assertThat(room.path("onWork").asBoolean()).isFalse();
    }
}
