package com.gridstore.huevista.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.ShadeRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shop's OWN choice of which paint companies it puts in front of people.
 *
 * A shop granted six companies by its distributor but stocking two had no way to say so:
 * all six appeared at its counter, in its kiosk, and to every customer it onboarded. This
 * suite covers the setting that fixes that, and — more importantly — the boundary between
 * it and the distributor's grant, which is where a "let the shop choose" feature can
 * quietly turn into "let the shop grant itself anything".
 *
 * The invariant under test throughout: the effective catalogue is the INTERSECTION, and
 * the shop's half can only ever subtract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ShopBrandVisibilityIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired BrandRepository brandRepository;
    @Autowired ShadeRepository shadeRepository;
    @Autowired PasswordEncoder passwordEncoder;

    // ── Fixtures ──────────────────────────────────────────────────────────

    private String tokenFor(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(login.getResponse().getContentAsString(),
                AuthResponse.class).getAccessToken();
    }

    /**
     * Three invented companies with a shade each. Invented rather than real because the
     * app seeds a real catalogue on startup and {@code brands.name} is unique — and it
     * means every assertion here is about WHICH companies come back, never how many.
     */
    private Brand[] seedCatalogue() {
        Brand a = brandRepository.save(Brand.builder().name("Testco Paints").slug("testco-paints").build());
        Brand b = brandRepository.save(Brand.builder().name("Rivalco Paints").slug("rivalco-paints").build());
        Brand c = brandRepository.save(Brand.builder().name("Thirdco Paints").slug("thirdco-paints").build());
        shadeRepository.save(Shade.builder().brand(a).shadeCode("T1").name("air breeze")
                .hexCode("#F3EDE8").shadeFamily("off whites").popularity(1).build());
        shadeRepository.save(Shade.builder().brand(b).shadeCode("R1").name("deep sea")
                .hexCode("#123456").shadeFamily("blues").popularity(2).build());
        shadeRepository.save(Shade.builder().brand(c).shadeCode("H1").name("warm clay")
                .hexCode("#B5651D").shadeFamily("browns").popularity(3).build());
        return new Brand[]{a, b, c};
    }

    private void seedAdmin() {
        userRepository.save(User.builder()
                .name("Root Admin").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
    }

    private String seedDistributor() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");
        mockMvc.perform(post("/api/admin/distributors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Arun","email":"dist@example.com","password":"password123",
                                 "companyName":"Shetty Trade","city":"Hubli","state":"Karnataka"}"""))
                .andExpect(status().isCreated());
        return tokenFor("dist@example.com", "password123");
    }

    /** A shop granted the given companies (or everything, when {@code brandIds} is null). */
    private void seedShop(String distToken, Long... brandIds) throws Exception {
        String grant = brandIds.length == 0 ? "" :
                ",\"brandIds\":[" + java.util.Arrays.stream(brandIds)
                        .map(String::valueOf).collect(java.util.stream.Collectors.joining(","))
                + "],\"brandsUnrestricted\":false";
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House","city":"Pune","state":"Maharashtra"%s}"""
                                .formatted(grant)))
                .andExpect(status().isCreated());
        putShopOnAPaidPlan();
    }

    /**
     * Put the shop on a paid plan.
     *
     * The free tier caps a shop at ONE paint company, which is a separate rule with its
     * own tests. Every test here is about the shop's OWN selection, so the cap has to be
     * lifted or it — not the thing under test — is what the assertions would be measuring.
     */
    private void putShopOnAPaidPlan() throws Exception {
        String adminToken = tokenFor("root@example.com", "password123");
        String userId = userRepository.findByEmail("shop@example.com").orElseThrow().getId();
        mockMvc.perform(post("/api/admin/users/" + userId + "/subscription")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"STARTER\",\"days\":30}"))
                .andExpect(status().isCreated());
    }

    /** End the paid plan, dropping the shop back to the free tier's one-company cap. */
    private void dropShopToTheFreeTier() throws Exception {
        String adminToken = tokenFor("root@example.com", "password123");
        String userId = userRepository.findByEmail("shop@example.com").orElseThrow().getId();
        mockMvc.perform(post("/api/admin/users/" + userId + "/subscription")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"FREE\",\"days\":30}"))
                .andExpect(status().isCreated());
    }

    private String shopOrgId() {
        User owner = userRepository.findByEmail("shop@example.com").orElseThrow();
        return organizationRepository.findByOwnerIdAndType(owner.getId(), OrgType.RETAILER)
                .stream().findFirst().orElseThrow().getId();
    }

    private java.util.List<String> slugsIn(MvcResult result, String field) throws Exception {
        com.fasterxml.jackson.databind.JsonNode body =
                objectMapper.readTree(result.getResponse().getContentAsString());
        java.util.List<String> slugs = new java.util.ArrayList<>();
        body.forEach(node -> slugs.add(node.path(field).asText()));
        return slugs;
    }

    /** The companies the shop's own tools actually offer right now. */
    private java.util.List<String> whatTheShopShows(String shopToken) throws Exception {
        MvcResult mine = mockMvc.perform(get("/api/shades/mine")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk()).andReturn();
        return slugsIn(mine, "brandSlug");
    }

    // ── The setting itself ────────────────────────────────────────────────

    @Test
    void the_options_offered_are_the_grant_not_the_whole_catalogue() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");

        // Thirdco was never granted, so it must not appear as a checkbox: a control that
        // silently does nothing is worse than no control.
        mockMvc.perform(get("/api/organizations/" + shopOrgId() + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(false))
                .andExpect(jsonPath("$.brands.length()").value(2))
                // Unset means "showing everything I carry", so every option reads as shown.
                .andExpect(jsonPath("$.brands[?(@.slug=='testco-paints')].shown").value(true))
                .andExpect(jsonPath("$.brands[?(@.slug=='rivalco-paints')].shown").value(true))
                .andExpect(jsonPath("$.brands[?(@.slug=='thirdco-paints')]").isEmpty());
    }

    @Test
    void choosing_one_company_hides_the_others_everywhere_at_once() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");

        assertThat(whatTheShopShows(shopToken))
                .containsExactlyInAnyOrder("testco-paints", "rivalco-paints");

        mockMvc.perform(put("/api/organizations/" + shopOrgId() + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":[" + brands[0].getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true))
                .andExpect(jsonPath("$.brands[?(@.slug=='testco-paints')].shown").value(true))
                .andExpect(jsonPath("$.brands[?(@.slug=='rivalco-paints')].shown").value(false));

        // The counter's own studio.
        assertThat(whatTheShopShows(shopToken)).containsOnly("testco-paints");

        // …and the brand picker that drives the company chooser.
        MvcResult myBrands = mockMvc.perform(get("/api/shades/mine/brands")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(myBrands, "slug")).containsOnly("testco-paints");

        // The public shopfront is a shared cache and must stay whole for everyone else.
        MvcResult publicList = mockMvc.perform(get("/api/shades")).andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(publicList, "brandSlug")).contains("testco-paints", "rivalco-paints");
    }

    @Test
    void show_all_lifts_the_shops_own_limit_again() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");
        String orgId = shopOrgId();

        mockMvc.perform(put("/api/organizations/" + orgId + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":[" + brands[0].getId() + "]}"))
                .andExpect(status().isOk());
        assertThat(whatTheShopShows(shopToken)).containsOnly("testco-paints");

        mockMvc.perform(put("/api/organizations/" + orgId + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(false));

        // Back to the full grant — and only the grant.
        assertThat(whatTheShopShows(shopToken))
                .containsExactlyInAnyOrder("testco-paints", "rivalco-paints");
    }

    /**
     * An empty selection is a real "show nothing", not a reset to "show everything".
     *
     * This is the whole reason the restriction is a FLAG on the organization rather than
     * being inferred from row count: "never configured" and "deselected every company"
     * are different intentions that both store zero rows, and guessing gets one backwards.
     */
    @Test
    void deselecting_everything_means_nothing_not_everything() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");

        mockMvc.perform(put("/api/organizations/" + shopOrgId() + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true));

        assertThat(whatTheShopShows(shopToken)).isEmpty();
    }

    /**
     * Saving the same selection twice must not blow up on the unique constraint.
     *
     * The write replaces the selection wholesale, and Hibernate orders inserts before
     * deletes — so re-selecting a company that was already selected re-inserted a row the
     * delete had not reached yet. Pressing Save twice is the most ordinary thing a user
     * does with a settings page.
     */
    @Test
    void saving_the_same_selection_twice_is_fine() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");
        String body = "{\"showAll\":false,\"brandIds\":[" + brands[0].getId() + "]}";

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put("/api/organizations/" + shopOrgId() + "/visible-brands")
                            .header("Authorization", "Bearer " + shopToken)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
        assertThat(whatTheShopShows(shopToken)).containsOnly("testco-paints");
    }

    // ── The boundary with the distributor's grant ─────────────────────────

    /**
     * The shop's selection SUBTRACTS from its grant; it can never add to it.
     *
     * Selecting a company the distributor never assigned has to be a no-op rather than a
     * self-service grant, which is the failure mode a "shop chooses its own brands"
     * setting invites.
     */
    @Test
    void a_shop_cannot_show_a_company_it_was_never_granted() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId());
        String shopToken = tokenFor("shop@example.com", "password123");

        // Ask for the granted one AND the one that was withheld.
        mockMvc.perform(put("/api/organizations/" + shopOrgId() + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":["
                                + brands[0].getId() + "," + brands[2].getId() + "]}"))
                .andExpect(status().isOk())
                // Thirdco is not even an option, so it cannot come back as one.
                .andExpect(jsonPath("$.brands.length()").value(1));

        assertThat(whatTheShopShows(shopToken)).containsOnly("testco-paints");
    }

    /**
     * A later revoke bites immediately, whatever the shop's own selection still says.
     *
     * This is the ordering that matters: intersecting grant-then-selection means a
     * company taken away upstream disappears even though the shop's row for it survives.
     * The other order would let a stale selection re-grant a revoked company.
     */
    @Test
    void a_distributor_revoke_beats_the_shops_selection() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");
        String orgId = shopOrgId();

        mockMvc.perform(put("/api/organizations/" + orgId + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":["
                                + brands[0].getId() + "," + brands[1].getId() + "]}"))
                .andExpect(status().isOk());
        assertThat(whatTheShopShows(shopToken))
                .containsExactlyInAnyOrder("testco-paints", "rivalco-paints");

        // The distributor narrows the grant to Testco only.
        mockMvc.perform(put("/api/hierarchy/retailers/" + orgId + "/brands")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unrestricted\":false,\"brandIds\":[" + brands[0].getId() + "]}"))
                .andExpect(status().isOk());

        assertThat(whatTheShopShows(shopToken)).containsOnly("testco-paints");
    }

    /**
     * A shop with no distributor limit at all can still choose what it shows.
     *
     * The two flags are independent: "my distributor grants me everything" must not mean
     * "I have to display everything".
     */
    @Test
    void an_unrestricted_shop_can_still_narrow_what_it_shows() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken); // no grant fields → unrestricted
        String shopToken = tokenFor("shop@example.com", "password123");

        Organization shop = organizationRepository.findById(shopOrgId()).orElseThrow();
        assertThat(shop.isBrandsRestricted()).isFalse();
        assertThat(whatTheShopShows(shopToken)).contains("testco-paints", "rivalco-paints");

        mockMvc.perform(put("/api/organizations/" + shopOrgId() + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":[" + brands[1].getId() + "]}"))
                .andExpect(status().isOk());

        assertThat(whatTheShopShows(shopToken)).containsOnly("rivalco-paints");
    }

    // ── Where the shop's selection meets the plan cap ─────────────────────

    /**
     * On the free tier, the ONE company a shop is left with is drawn from what it says it
     * stocks — not nominated by the plan and then intersected away.
     *
     * The ordering is the whole test. The plan cap keeps a single company; if it ran
     * before the shop's selection it would pin the free tier's nominated brand and then
     * intersect the shop's list against it, leaving a free shop that stocks anything else
     * with an EMPTY catalogue — unable to sell at all, over a company it never chose to
     * carry. Applying the shop's choice first means the survivor is one it actually has.
     */
    @Test
    void a_free_shop_keeps_a_company_it_actually_stocks() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");

        mockMvc.perform(put("/api/organizations/" + shopOrgId() + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":[" + brands[1].getId() + "]}"))
                .andExpect(status().isOk());

        // Drop back to the free tier, where the plan allows exactly one company.
        dropShopToTheFreeTier();

        // Exactly one company, and it is the one the shop said it stocks — not empty.
        java.util.List<String> showing = whatTheShopShows(shopToken);
        assertThat(showing).containsOnly("rivalco-paints");
    }

    /**
     * The refusal has to name the limit that actually bit.
     *
     * A free-tier shop being told to go and untick a box in its own settings is useless
     * advice — nothing is ticked, and the fix is a plan. Inferring "the shop hid it" from
     * "granted but not offered" was right while the grant and the selection were the only
     * two limits, and became wrong the moment the plan cap was added.
     */
    @Test
    void a_free_shop_is_told_the_plan_is_the_limit_not_its_settings() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");
        dropShopToTheFreeTier();

        mockMvc.perform(post("/api/organizations/" + shopOrgId() + "/access-codes")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Ravi","allowedBrands":["Testco Paints","Rivalco Paints"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(
                        result.getResolvedException() == null ? ""
                                : String.valueOf(result.getResolvedException().getMessage()))
                        .contains("plan covers one paint company"));
    }

    // ── Who may change it ─────────────────────────────────────────────────

    @Test
    void a_customer_cannot_change_what_a_shop_shows() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());

        userRepository.save(User.builder()
                .name("Walk In").email("walkin@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.CUSTOMER).build());
        String customerToken = tokenFor("walkin@example.com", "password123");

        mockMvc.perform(put("/api/organizations/" + shopOrgId() + "/visible-brands")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":[]}"))
                .andExpect(status().isForbidden());
    }

    /**
     * An admin can operate the switch on any shop's behalf.
     *
     * Without it the only way to rescue a shop that has hidden every company — and so
     * cannot sell anything — is an edit against the database.
     */
    @Test
    void an_admin_can_fix_a_shop_that_hid_everything() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");
        String orgId = shopOrgId();

        mockMvc.perform(put("/api/organizations/" + orgId + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":[]}"))
                .andExpect(status().isOk());
        assertThat(whatTheShopShows(shopToken)).isEmpty();

        mockMvc.perform(put("/api/organizations/" + orgId + "/visible-brands")
                        .header("Authorization", "Bearer " + tokenFor("root@example.com", "password123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":true}"))
                .andExpect(status().isOk());

        assertThat(whatTheShopShows(shopToken))
                .containsExactlyInAnyOrder("testco-paints", "rivalco-paints");
    }

    // ── Downstream: what the shop hides, it cannot hand out ───────────────

    /**
     * A hidden company cannot be put on a customer's access code either, and the refusal
     * has to name the right party to go and see.
     *
     * "Ask your distributor" is useless advice for a company the shop is holding back by
     * its own setting — it sends the shop to argue with someone who already granted it.
     */
    @Test
    void a_hidden_company_cannot_be_offered_on_an_access_code() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        seedShop(distToken, brands[0].getId(), brands[1].getId());
        String shopToken = tokenFor("shop@example.com", "password123");

        mockMvc.perform(put("/api/organizations/" + shopOrgId() + "/visible-brands")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showAll\":false,\"brandIds\":[" + brands[0].getId() + "]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/organizations/" + shopOrgId() + "/access-codes")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Ravi","allowedBrands":["Rivalco Paints"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(
                        result.getResolvedException() == null ? ""
                                : String.valueOf(result.getResolvedException().getMessage()))
                        .contains("isn't showing"));
    }
}
