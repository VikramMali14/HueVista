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
import com.gridstore.huevista.library.repository.FreeProjectTemplateRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import com.razorpay.RazorpayClient;
import jakarta.persistence.EntityManager;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public gallery, and the one thing a published room could not do: change.
 *
 * Two behaviours meet here. {@code GET /api/free-projects} is what the marketing
 * site reads, so it must answer without a session and must never mention a hidden
 * room — hiding one in the admin console IS how a room comes off the site. And
 * {@code POST /api/admin/free-projects/{id}/refresh} is how a mask gets fixed
 * after publishing, which was previously impossible: publishing takes a COPY of
 * the walls, so editing the original changed nothing on the shelf.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class FreeProjectGalleryIntegrationTest {

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
    @Autowired EntityManager entityManager;

    // ── Fixtures ──────────────────────────────────────────────────────────

    private User admin() {
        return userRepository.save(User.builder()
                .name("Root Admin").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
    }

    private String adminToken() throws Exception {
        return tokenFor("root@example.com");
    }

    /** An ordinary signed-in visitor — the person the gallery's paint button is for. */
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

    /** The slug the gallery card carries — the only handle a visitor ever holds. */
    private String slugOf(String templateId) {
        return templateRepository.findById(templateId).orElseThrow().getSlug();
    }

    /** A tiny stored file, so the copy the library makes has real bytes to read. */
    private String store(User owner, String name, String contentType) throws IOException {
        return storageService.store(new byte[]{1, 2, 3, 4}, owner.getId(), name, contentType);
    }

    /**
     * A project with {@code walls} masked surfaces — the shape publishing requires.
     * Each wall carries an applied colour, since that is what a gallery card shows.
     */
    private Project projectWith(User owner, int walls) throws IOException {
        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(owner)
                .originalFilename("room.jpg")
                .storageKey(store(owner, "room.jpg", "image/jpeg"))
                .contentType("image/jpeg")
                .fileSize(4L)
                .width(1600).height(1200)
                .imageType(ImageType.INDOOR)
                .build());
        Project project = projectRepository.save(Project.builder()
                .user(owner).image(image).name("Sunlit hall").roomType("Living room")
                .status(ProjectStatus.SEGMENTED).build());
        for (int i = 0; i < walls; i++) {
            String mask = store(owner, "mask-" + i + ".png", "image/png");
            regionRepository.save(Region.builder()
                    .project(project)
                    .label(i == 0 ? "Main wall" : "Wall " + (i + 1))
                    .category(RegionCategory.MAIN_WALL)
                    .maskUrl(mask)
                    .maskData(mask)
                    .appliedHexCode(i == 0 ? "#D9C7AE" : "#8CC7D9")
                    .appliedShadeCode(i == 0 ? "AP-1001" : "AP-2002")
                    .displayOrder(i)
                    .manual(false)
                    .build());
        }
        return project;
    }

    /** Publish {@code project} and return the created template's id. */
    private String publish(String token, Project project, boolean published) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/admin/free-projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","title":"Sunlit hall","space":"INTERIOR",
                                 "roomKey":"LIVING_ROOM","published":%s}"""
                                .formatted(project.getId(), published)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("id").asText();
    }

    private JsonNode gallery() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/free-projects"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    // ── The public shelf ──────────────────────────────────────────────────

    @Test
    void the_gallery_answers_without_a_session_and_carries_the_rooms_colours() throws Exception {
        User root = admin();
        String token = adminToken();
        publish(token, projectWith(root, 2), true);

        // No Authorization header at all — this is the marketing site's read.
        JsonNode rooms = gallery();
        assertThat(rooms).hasSize(1);
        JsonNode room = rooms.get(0);
        assertThat(room.path("title").asText()).isEqualTo("Sunlit hall");
        assertThat(room.path("roomLabel").asText()).isNotBlank();
        assertThat(room.path("wallCount").asInt()).isEqualTo(2);
        assertThat(room.path("imageUrl").asText()).isNotBlank();
        assertThat(room.path("colours")).hasSize(2);
        assertThat(room.path("colours").get(0).path("shadeCode").asText()).isEqualTo("AP-1001");
    }

    /**
     * The admin DTO's internals must not leak onto the marketing site. Mask URLs
     * are the sharp one — they are readable files, and the public shape has no
     * business handing out a link to every wall outline.
     */
    @Test
    void the_public_shape_carries_no_masks_and_no_internal_handles() throws Exception {
        User root = admin();
        publish(adminToken(), projectWith(root, 1), true);

        JsonNode room = gallery().get(0);
        assertThat(room.has("regions")).isFalse();
        assertThat(room.has("sourceProjectId")).isFalse();
        assertThat(room.has("copiesInUse")).isFalse();
        assertThat(room.has("timesUsed")).isFalse();
        assertThat(room.has("id")).isFalse();
    }

    @Test
    void a_hidden_room_is_absent_from_the_gallery_and_from_its_own_url() throws Exception {
        User root = admin();
        String token = adminToken();
        String templateId = publish(token, projectWith(root, 1), true);

        assertThat(gallery()).hasSize(1);

        mockMvc.perform(patch("/api/admin/free-projects/" + templateId + "/published?published=false")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(gallery()).isEmpty();
        // Absent, not forbidden: whether a hidden draft exists is not a public fact.
        mockMvc.perform(get("/api/free-projects/sunlit-hall")).andExpect(status().isNotFound());
    }

    @Test
    void a_room_published_hidden_never_appears_at_all() throws Exception {
        User root = admin();
        publish(adminToken(), projectWith(root, 1), false);
        assertThat(gallery()).isEmpty();
    }

    /** The gallery's own photographs load for a browser with no session. */
    @Test
    void the_public_file_route_serves_library_files_and_refuses_everything_else() throws Exception {
        User root = admin();
        publish(adminToken(), projectWith(root, 1), true);

        String url = gallery().get(0).path("imageUrl").asText();
        // Local-storage mode: an app-relative path this route serves. (With S3
        // configured the URL is presigned and absolute, and never comes here.)
        if (url.startsWith("/api/free-projects/files/")) {
            mockMvc.perform(get(url)).andExpect(status().isOk());
        }
        // A key outside the library prefix is refused, session or no session.
        mockMvc.perform(get("/api/free-projects/files/" + root.getId() + "/secret.jpg"))
                .andExpect(status().isForbidden());
        // …including one that tries to climb out of it. Spring's own request
        // firewall rejects the traversal before the controller is reached (400
        // rather than 403), so what is pinned here is that it is refused, not
        // which of the two layers happens to refuse it first — the controller's
        // guard stays in place regardless, since the firewall is configurable.
        mockMvc.perform(get("/api/free-projects/files/free-projects/../" + root.getId() + "/secret.jpg"))
                .andExpect(status().is4xxClientError());
    }

    // ── Refreshing a published room ───────────────────────────────────────

    /**
     * The bug this endpoint exists for: publishing froze the walls, so a mask
     * could never be corrected afterwards. Adding a wall in the studio and
     * refreshing must reach the published room.
     */
    @Test
    void refreshing_brings_a_wall_added_after_publishing_onto_the_shelf() throws Exception {
        User root = admin();
        String token = adminToken();
        Project project = projectWith(root, 1);
        String templateId = publish(token, project, true);

        assertThat(gallery().get(0).path("wallCount").asInt()).isEqualTo(1);

        // The admin goes back to the studio and marks a wall that had been missed.
        String mask = store(root, "extra.png", "image/png");
        regionRepository.save(Region.builder()
                .project(project).label("Accent wall").category(RegionCategory.ACCENT_WALL)
                .maskUrl(mask).maskData(mask)
                .appliedHexCode("#C78CD9").appliedShadeCode("AP-3003")
                .displayOrder(1).manual(true).build());

        // Publishing took a copy, so the shelf has not moved on its own…
        assertThat(gallery().get(0).path("wallCount").asInt()).isEqualTo(1);

        mockMvc.perform(post("/api/admin/free-projects/" + templateId + "/refresh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCount").value(2));

        JsonNode room = gallery().get(0);
        assertThat(room.path("wallCount").asInt()).isEqualTo(2);
        assertThat(room.path("colours")).hasSize(2);
    }

    /** Everything that identifies the room survives — that is the point of it. */
    @Test
    void refreshing_keeps_the_slug_the_shelf_position_and_the_published_state() throws Exception {
        User root = admin();
        String token = adminToken();
        Project project = projectWith(root, 1);
        String templateId = publish(token, project, true);
        var before = templateRepository.findById(templateId).orElseThrow();
        String slug = before.getSlug();
        int order = before.getDisplayOrder();

        mockMvc.perform(post("/api/admin/free-projects/" + templateId + "/refresh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(templateId))
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.displayOrder").value(order))
                .andExpect(jsonPath("$.published").value(true));
    }

    /**
     * A refresh writes NEW files rather than over the old ones, so a copy someone
     * is halfway through painting does not have its walls move mid-session.
     */
    @Test
    void refreshing_leaves_existing_copies_on_the_files_they_started_with() throws Exception {
        User root = admin();
        String token = adminToken();
        Project project = projectWith(root, 1);
        String templateId = publish(token, project, true);

        // Someone opens a copy; it points at the template's current photo.
        mockMvc.perform(post("/api/admin/free-projects/" + templateId + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        String keyTheCopyHolds = templateRepository.findById(templateId).orElseThrow().getImageStorageKey();

        mockMvc.perform(post("/api/admin/free-projects/" + templateId + "/refresh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String keyAfter = templateRepository.findById(templateId).orElseThrow().getImageStorageKey();
        assertThat(keyAfter).isNotEqualTo(keyTheCopyHolds);
        // The copy's file is still there — it was in use, so it was not purged.
        assertThat(storageService.load(keyTheCopyHolds)).isNotEmpty();
    }

    @Test
    void refreshing_refuses_when_the_project_behind_the_room_has_lost_its_walls() throws Exception {
        User root = admin();
        String token = adminToken();
        Project project = projectWith(root, 1);
        String templateId = publish(token, project, true);

        regionRepository.deleteAll(regionRepository.findByProjectIdOrderByDisplayOrderAsc(project.getId()));

        mockMvc.perform(post("/api/admin/free-projects/" + templateId + "/refresh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        // …and the room is untouched rather than emptied.
        assertThat(gallery().get(0).path("wallCount").asInt()).isEqualTo(1);
    }

    @Test
    void refreshing_is_admin_only() throws Exception {
        User root = admin();
        String token = adminToken();
        String templateId = publish(token, projectWith(root, 1), true);

        mockMvc.perform(post("/api/admin/free-projects/" + templateId + "/refresh"))
                .andExpect(status().isUnauthorized());
    }

    // ── Painting a room from the gallery ──────────────────────────────────
    //
    // The shelf was readable by everyone and startable by nobody but an admin, so
    // the gallery was a page you could look at and not use. These cover the route
    // that opened it: by SLUG (the only handle a card carries), signed-in, free.

    @Test
    void an_ordinary_visitor_can_take_a_room_from_the_gallery_and_paint_it() throws Exception {
        User root = admin();
        String adminToken = adminToken();
        String slug = slugOf(publish(adminToken, projectWith(root, 2), true));

        customer();
        String token = tokenFor("asha@example.com");

        MvcResult res = mockMvc.perform(post("/api/free-projects/" + slug + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                // Born SEGMENTED with its walls already on it: the studio can open it
                // straight away, with no upload and no wall detection in between.
                .andExpect(jsonPath("$.status").value("SEGMENTED"))
                .andExpect(jsonPath("$.regionCount").value(2))
                .andExpect(jsonPath("$.projectId").isNotEmpty())
                .andReturn();

        String projectId = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("projectId").asText();
        Project copy = projectRepository.findById(projectId).orElseThrow();
        // It belongs to the visitor, not to the admin who published the room.
        assertThat(copy.getUser().getEmail()).isEqualTo("asha@example.com");
        assertThat(regionRepository.findByProjectIdOrderByDisplayOrderAsc(projectId)).hasSize(2);
    }

    /** A valid 1x1 PNG, so the request passes validation and reaches the guard under test. */
    private static final String ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    /**
     * A library room's WALLS are fixed; its paint is not.
     *
     * <p>The room is a copy of a finished, curated template: its surfaces were cut once,
     * by an admin, and the shelf's thumbnail is a promise about what a copy looks like. A
     * copy that re-cuts its own walls quietly stops being the room it names, and nothing
     * on the shelf shows that it has. There is also nothing to repair — a room the
     * account uploaded can hand-mark its way out of a bad detection, but these walls were
     * correct when they were published.
     *
     * <p>So four writes are refused and everything else is untouched. Painting in
     * particular has to keep working, because it is the entire point of taking a room off
     * the shelf.
     */
    @Test
    void a_library_room_refuses_wall_edits_but_still_takes_paint() throws Exception {
        User root = admin();
        String adminToken = adminToken();
        String slug = slugOf(publish(adminToken, projectWith(root, 2), true));

        customer();
        String token = tokenFor("asha@example.com");

        MvcResult res = mockMvc.perform(post("/api/free-projects/" + slug + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn();
        String projectId = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("projectId").asText();
        Long regionId = regionRepository.findByProjectIdOrderByDisplayOrderAsc(projectId)
                .get(0).getId();

        // Drawing a new wall.
        mockMvc.perform(post("/api/projects/" + projectId + "/regions/custom-mask")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maskBase64\":\"" + ONE_PIXEL_PNG
                                + "\",\"category\":\"MAIN_WALL\",\"label\":\"Wall\"}"))
                .andExpect(status().isConflict());

        // Re-cutting one that is already there.
        mockMvc.perform(put("/api/projects/" + projectId + "/regions/" + regionId + "/mask")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maskBase64\":\"" + ONE_PIXEL_PNG + "\"}"))
                .andExpect(status().isConflict());

        // Removing one.
        mockMvc.perform(delete("/api/projects/" + projectId + "/regions/" + regionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        // …and the thing the room exists for still works.
        mockMvc.perform(put("/api/projects/" + projectId + "/regions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"regionId\":" + regionId + ",\"hexCode\":\"#88AACC\"}]"))
                .andExpect(status().isNoContent());
        assertThat(regionRepository.findByProjectIdOrderByDisplayOrderAsc(projectId)).hasSize(2);
    }

    /**
     * The copy names the library's files rather than owning new ones — that is what
     * makes it free, and it is the reason the ordinary project cleanup has to check
     * before deleting anything under {@code free-projects/}.
     */
    @Test
    void the_copy_reuses_the_librarys_files_instead_of_uploading_its_own() throws Exception {
        User root = admin();
        String adminToken = adminToken();
        String templateId = publish(adminToken, projectWith(root, 1), true);
        String slug = slugOf(templateId);

        customer();
        String token = tokenFor("asha@example.com");

        MvcResult res = mockMvc.perform(post("/api/free-projects/" + slug + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn();
        String projectId = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("projectId").asText();

        String templateKey = templateRepository.findById(templateId).orElseThrow().getImageStorageKey();
        Project copy = projectRepository.findById(projectId).orElseThrow();
        assertThat(copy.getImage().getStorageKey()).isEqualTo(templateKey);
        assertThat(templateKey).startsWith(FreeProjectStorage.PREFIX);
    }

    @Test
    void an_anonymous_visitor_is_refused_and_the_gallery_stays_readable() throws Exception {
        User root = admin();
        String adminToken = adminToken();
        String slug = slugOf(publish(adminToken, projectWith(root, 1), true));

        // Reading the shelf needs no session…
        mockMvc.perform(get("/api/free-projects")).andExpect(status().isOk());
        // …but taking a room away does. Otherwise this is a free project factory
        // for anyone who can reach the site.
        mockMvc.perform(post("/api/free-projects/" + slug + "/start"))
                .andExpect(status().isUnauthorized());
        assertThat(projectRepository.count()).isEqualTo(1); // only the admin's own
    }

    /**
     * A hidden room reads as ABSENT, not as forbidden — the same answer GET gives.
     * Whether an unpublished draft sits behind a guessed slug is not a public fact,
     * and this endpoint is reachable by anyone who can make an account.
     */
    @Test
    void a_hidden_room_cannot_be_painted_and_does_not_admit_that_it_exists() throws Exception {
        User root = admin();
        String adminToken = adminToken();
        String slug = slugOf(publish(adminToken, projectWith(root, 1), false));

        customer();
        String token = tokenFor("asha@example.com");

        mockMvc.perform(post("/api/free-projects/" + slug + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void a_slug_that_names_nothing_is_a_404_rather_than_a_500() throws Exception {
        customer();
        String token = tokenFor("asha@example.com");

        mockMvc.perform(post("/api/free-projects/no-such-room/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** Two people opening the same room get two projects, not one shared one. */
    @Test
    void the_same_room_can_be_painted_by_more_than_one_person() throws Exception {
        User root = admin();
        String adminToken = adminToken();
        String slug = slugOf(publish(adminToken, projectWith(root, 1), true));

        customer();
        String first = tokenFor("asha@example.com");
        mockMvc.perform(post("/api/free-projects/" + slug + "/start")
                        .header("Authorization", "Bearer " + first))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/free-projects/" + slug + "/start")
                        .header("Authorization", "Bearer " + first))
                .andExpect(status().isCreated());

        // The admin's source project, plus one per copy.
        assertThat(projectRepository.count()).isEqualTo(3);

        // The usage counter is bumped by a @Modifying JPQL update, which writes past
        // the persistence context — inside this test's transaction the cached entity
        // still reads 0. Clear it so the assertion sees the row, not the cache.
        entityManager.clear();
        assertThat(templateRepository.findBySlug(slug).orElseThrow().getTimesUsed()).isEqualTo(2);
    }
}
