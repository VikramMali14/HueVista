package com.gridstore.huevista.hierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.model.AppFeature;
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
 * What a distributor grants a shop, and whether the shop is actually held to it.
 *
 * The brand assignment existed before this suite but was only enforced when a shop
 * issued a customer access code — a restricted shop could still browse the whole
 * catalogue in its own tools, which is the gap these tests close. They cover both
 * halves of the grant (paint companies + pages), set both at creation time rather
 * than as a follow-up edit, and assert the awkward cases the three-state
 * restricted/empty/unrestricted shape exists for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class DistributorAccessControlIntegrationTest {

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
        return objectMapper.readValue(login.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }

    private void seedAdmin() {
        userRepository.save(User.builder()
                .name("Root Admin").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
    }

    /**
     * Two companies with one shade each, so brand filtering has something to bite on.
     *
     * The names are deliberately invented rather than real ones: the app seeds a real
     * catalogue (Asian Paints and friends) on startup, and {@code brands.name} is
     * unique, so reusing a real company name fails every test on insert. It also means
     * assertions here must be about WHICH brands come back, never how many rows the
     * whole catalogue holds.
     */
    private Brand[] seedCatalogue() {
        Brand granted = brandRepository.save(Brand.builder().name("Testco Paints").slug("testco-paints").build());
        Brand withheld = brandRepository.save(Brand.builder().name("Rivalco Paints").slug("rivalco-paints").build());
        shadeRepository.save(Shade.builder().brand(granted).shadeCode("T9436").name("air breeze")
                .hexCode("#F3EDE8").shadeFamily("off whites").popularity(1).build());
        shadeRepository.save(Shade.builder().brand(withheld).shadeCode("R100").name("deep sea")
                .hexCode("#123456").shadeFamily("blues").popularity(2).build());
        return new Brand[]{granted, withheld};
    }

    /**
     * A real Asian Paints shade, on the brand row the paint-line seeder already creates.
     *
     * The free tier carries exactly one paint company and that company is Asian Paints
     * (Plan.FREE_TIER_BRAND_SLUG), so any test about the plan cap needs it to be a
     * company with shades in it rather than an empty brand row.
     */
    private Brand seedAsianPaints() {
        Brand ap = brandRepository.findBySlug("asian-paints")
                .orElseGet(() -> brandRepository.save(
                        Brand.builder().name("Asian Paints").slug("asian-paints").build()));
        shadeRepository.save(Shade.builder().brand(ap).shadeCode("AP1").name("ivory mist")
                .hexCode("#EFE7D8").shadeFamily("off whites").popularity(3).build());
        return ap;
    }

    /**
     * Put a shop on a paid tier.
     *
     * The tests in this class are about what a DISTRIBUTOR grants, and the free tier
     * applies a company cap of its own on top (see {@code BrandAccessService.capToPlan})
     * — which would otherwise narrow the shop before the grant under test had a chance
     * to. Paying takes the plan out of the picture so each assertion is about one thing.
     * The plan cap has its own tests further down.
     */
    private void putShopOnAPaidPlan(String shopEmail) throws Exception {
        String adminToken = tokenFor("root@example.com", "password123");
        String userId = userRepository.findByEmail(shopEmail).orElseThrow().getId();
        mockMvc.perform(post("/api/admin/users/" + userId + "/subscription")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"STARTER\",\"days\":30}"))
                .andExpect(status().isCreated());
    }

    /** The brand slugs present in a JSON array response, for content-based assertions. */
    private java.util.List<String> slugsIn(MvcResult result, String field) throws Exception {
        com.fasterxml.jackson.databind.JsonNode body =
                objectMapper.readTree(result.getResponse().getContentAsString());
        java.util.List<String> slugs = new java.util.ArrayList<>();
        body.forEach(node -> slugs.add(node.path(field).asText()));
        return slugs;
    }

    /** Admin creates a distributor, returns that distributor's access token. */
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

    private String shopOrgId(String ownerEmail) {
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        return organizationRepository.findByOwnerIdAndType(owner.getId(), OrgType.RETAILER)
                .stream().findFirst().orElseThrow().getId();
    }

    // ── Grants applied at shop creation ───────────────────────────────────

    @Test
    void distributor_sets_brands_and_pages_while_creating_the_shop() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();

        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House","city":"Pune","state":"Maharashtra",
                                 "brandIds":[%d],"brandsUnrestricted":false,
                                 "features":["COLOR_FINDER","CATALOGUE"],"featuresUnrestricted":false}"""
                                .formatted(brands[0].getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("RETAILER"));

        Organization shop = organizationRepository.findById(shopOrgId("shop@example.com")).orElseThrow();
        assertThat(shop.isBrandsRestricted()).isTrue();
        assertThat(shop.isFeaturesRestricted()).isTrue();

        // The shop's own view of what it may do.
        String shopToken = tokenFor("shop@example.com", "password123");
        mockMvc.perform(get("/api/hierarchy/my-access").header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("RETAILER"))
                .andExpect(jsonPath("$.brandsRestricted").value(true))
                .andExpect(jsonPath("$.allowedBrands.length()").value(1))
                .andExpect(jsonPath("$.allowedBrands[0]").value("Testco Paints"))
                .andExpect(jsonPath("$.featuresRestricted").value(true))
                .andExpect(jsonPath("$.allowedFeatures.length()").value(2))
                .andExpect(jsonPath("$.allowedPaths", org.hamcrest.Matchers.hasItem("/color-finder")));
    }

    @Test
    void omitting_the_grant_fields_leaves_the_shop_unrestricted() throws Exception {
        seedCatalogue();
        String distToken = seedDistributor();

        // Exactly the payload the previous frontend sent — no access fields at all.
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House"}"""))
                .andExpect(status().isCreated());

        Organization shop = organizationRepository.findById(shopOrgId("shop@example.com")).orElseThrow();
        assertThat(shop.isBrandsRestricted()).isFalse();
        assertThat(shop.isFeaturesRestricted()).isFalse();
    }

    // ── Enforcement on the catalogue ──────────────────────────────────────

    @Test
    void restricted_shop_only_sees_its_own_brands_but_the_public_catalogue_stays_whole() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House",
                                 "brandIds":[%d],"brandsUnrestricted":false}"""
                                .formatted(brands[0].getId())))
                .andExpect(status().isCreated());
        String shopToken = tokenFor("shop@example.com", "password123");

        // The shop's own tools see only the granted company — not just "fewer rows",
        // but specifically none from the company its distributor withheld.
        MvcResult mine = mockMvc.perform(get("/api/shades/mine").header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(mine, "brandSlug")).containsOnly("testco-paints");

        MvcResult myBrands = mockMvc.perform(get("/api/shades/mine/brands")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(myBrands, "slug")).containsOnly("testco-paints");

        // …while the anonymous shopfront still carries both. Filtering it per-caller
        // would break the shared cache for every visitor to serve one shop.
        MvcResult publicList = mockMvc.perform(get("/api/shades")).andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(publicList, "brandSlug")).contains("testco-paints", "rivalco-paints");
    }

    @Test
    void an_unrestricted_shop_and_a_distributor_both_see_the_whole_catalogue() throws Exception {
        seedCatalogue();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House"}"""))
                .andExpect(status().isCreated());
        // "Unrestricted" here means by the DISTRIBUTOR. A free shop is still capped to one
        // company by its own plan, which is a separate rule with its own tests below.
        putShopOnAPaidPlan("shop@example.com");

        MvcResult shopView = mockMvc.perform(get("/api/shades/mine")
                        .header("Authorization", "Bearer " + tokenFor("shop@example.com", "password123")))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(shopView, "brandSlug")).contains("testco-paints", "rivalco-paints");

        // A distributor has no shop org of its own — the restriction must not
        // accidentally apply upward and hide the catalogue from the granter.
        MvcResult distView = mockMvc.perform(get("/api/shades/mine")
                        .header("Authorization", "Bearer " + distToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(distView, "brandSlug")).contains("testco-paints", "rivalco-paints");
    }

    /**
     * The companies a shop unlocks ON A CODE must limit what that customer is served.
     *
     * This was enforced nowhere. A shop picks companies when issuing a code, and for a
     * customer who redeemed into an ACCOUNT — the primary walk-in route through /redeem —
     * the catalogue they got back was the WHOLE platform: {@code allowedBrandSlugsForUser}
     * answered "unrestricted" for every non-retailer, and the only filtering that existed
     * lived in a cookie in the anonymous guest's own browser.
     */
    @Test
    void a_customers_catalogue_is_limited_to_the_companies_on_their_code() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        // The shop carries both companies…
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House",
                                 "brandIds":[%d,%d],"brandsUnrestricted":false}"""
                                .formatted(brands[0].getId(), brands[1].getId())))
                .andExpect(status().isCreated());
        // The shop carries two companies only because it pays for them: the free tier
        // caps a shop at one, which is a separate rule with its own tests below.
        putShopOnAPaidPlan("shop@example.com");
        String shopToken = tokenFor("shop@example.com", "password123");
        String orgId = shopOrgId("shop@example.com");

        // …but unlocks only ONE of them for this customer.
        MvcResult issued = mockMvc.perform(post("/api/organizations/" + orgId + "/access-codes")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Anjali","projectQuota":1,
                                 "allowedBrands":["Testco Paints"]}"""))
                .andExpect(status().isCreated()).andReturn();
        String code = objectMapper.readTree(issued.getResponse().getContentAsString())
                .path("code").asText();

        // Redeeming into an account and asking for "my" catalogue.
        MvcResult redeemed = mockMvc.perform(post("/api/access-codes/redeem-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String customerToken = objectMapper.readTree(redeemed.getResponse().getContentAsString())
                .path("accessToken").asText();

        MvcResult customerView = mockMvc.perform(get("/api/shades/mine")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(customerView, "brandSlug")).containsOnly("testco-paints");
    }

    /**
     * A code cannot outlive its shop's own grant. If the distributor later revokes a
     * company, codes printed while the shop still carried it stop offering it too —
     * otherwise a revocation is undone by every code issued before it.
     */
    @Test
    void a_code_cannot_offer_a_company_the_shop_has_since_lost() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House",
                                 "brandIds":[%d,%d],"brandsUnrestricted":false}"""
                                .formatted(brands[0].getId(), brands[1].getId())))
                .andExpect(status().isCreated());
        // The shop carries two companies only because it pays for them: the free tier
        // caps a shop at one, which is a separate rule with its own tests below.
        putShopOnAPaidPlan("shop@example.com");
        String shopToken = tokenFor("shop@example.com", "password123");
        String orgId = shopOrgId("shop@example.com");

        MvcResult issued = mockMvc.perform(post("/api/organizations/" + orgId + "/access-codes")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Anjali","projectQuota":1,
                                 "allowedBrands":["Testco Paints","Rivalco Paints"]}"""))
                .andExpect(status().isCreated()).andReturn();
        String code = objectMapper.readTree(issued.getResponse().getContentAsString())
                .path("code").asText();

        // The distributor now pulls one company back.
        mockMvc.perform(put("/api/hierarchy/retailers/" + orgId + "/brands")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandIds\":[%d],\"unrestricted\":false}".formatted(brands[0].getId())))
                .andExpect(status().isOk());

        MvcResult redeemed = mockMvc.perform(post("/api/access-codes/redeem-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String customerToken = objectMapper.readTree(redeemed.getResponse().getContentAsString())
                .path("accessToken").asText();

        MvcResult customerView = mockMvc.perform(get("/api/shades/mine")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(customerView, "brandSlug")).containsOnly("testco-paints");
    }

    // ── The three-state contract ──────────────────────────────────────────

    @Test
    void revoking_every_page_grants_nothing_rather_than_everything() throws Exception {
        seedCatalogue();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House"}"""))
                .andExpect(status().isCreated());
        String orgId = shopOrgId("shop@example.com");

        // An empty list with unrestricted=false is a real revoke-everything…
        mockMvc.perform(put("/api/hierarchy/retailers/" + orgId + "/features")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"features":[],"unrestricted":false}"""))
                .andExpect(status().isOk());

        String shopToken = tokenFor("shop@example.com", "password123");
        mockMvc.perform(get("/api/hierarchy/my-access").header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featuresRestricted").value(true))
                .andExpect(jsonPath("$.allowedFeatures.length()").value(0));

        // …and unrestricted=true is the way to hand the whole product back.
        mockMvc.perform(put("/api/hierarchy/retailers/" + orgId + "/features")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"features":[],"unrestricted":true}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/hierarchy/my-access").header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featuresRestricted").value(false));
    }

    @Test
    void unknown_feature_keys_are_ignored_rather_than_failing_the_save() throws Exception {
        seedCatalogue();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House"}"""))
                .andExpect(status().isCreated());
        String orgId = shopOrgId("shop@example.com");

        // "colour-finder" is the hyphenated route spelling; RETIRED_PAGE is a key
        // an older frontend might still hold. One stale entry must not lose the save.
        mockMvc.perform(put("/api/hierarchy/retailers/" + orgId + "/features")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"features":["color-finder","RETIRED_PAGE"],"unrestricted":false}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/hierarchy/my-access")
                        .header("Authorization", "Bearer " + tokenFor("shop@example.com", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedFeatures.length()").value(1))
                .andExpect(jsonPath("$.allowedFeatures[0]").value("COLOR_FINDER"));
    }

    @Test
    void the_creation_form_can_list_what_is_grantable_before_any_shop_exists() throws Exception {
        seedCatalogue();
        String distToken = seedDistributor();

        MvcResult res = mockMvc.perform(get("/api/hierarchy/grantable")
                        .header("Authorization", "Bearer " + distToken))
                .andExpect(status().isOk())
                // Every page is offered, and none is pre-assigned — there is no shop yet.
                .andExpect(jsonPath("$.features[0].assigned").value(false))
                .andExpect(jsonPath("$.brands[0].assigned").value(false))
                .andReturn();

        com.fasterxml.jackson.databind.JsonNode body =
                objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.path("features")).hasSize(AppFeature.values().length);
        // Brand ids are what the create call sends back, so they must be present.
        assertThat(body.path("brands").get(0).path("id").asLong()).isPositive();
    }

    @Test
    void the_creation_form_only_offers_companies_that_have_shades() throws Exception {
        seedCatalogue();
        // A company with product lines but no shades — the shape PaintLineSeeder leaves
        // behind for brands whose catalogue was never uploaded. Offering it would let a
        // distributor limit a new shop to a company that has nothing to show.
        brandRepository.save(Brand.builder().name("Emptyco Paints").slug("emptyco-paints").build());
        String distToken = seedDistributor();

        MvcResult res = mockMvc.perform(get("/api/hierarchy/grantable")
                        .header("Authorization", "Bearer " + distToken))
                .andExpect(status().isOk())
                .andReturn();

        com.fasterxml.jackson.databind.JsonNode brands =
                objectMapper.readTree(res.getResponse().getContentAsString()).path("brands");
        java.util.List<String> slugs = new java.util.ArrayList<>();
        brands.forEach(node -> slugs.add(node.path("slug").asText()));
        assertThat(slugs).contains("testco-paints", "rivalco-paints").doesNotContain("emptyco-paints");
    }

    @Test
    void a_shops_editor_keeps_showing_a_company_it_already_holds() throws Exception {
        Brand[] brands = seedCatalogue();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House",
                                 "brandIds":[%d],"brandsUnrestricted":false}"""
                                .formatted(brands[0].getId())))
                .andExpect(status().isCreated());
        String orgId = shopOrgId("shop@example.com");

        // The company's last shade goes; the grant does not. The editor saves the whole
        // selection at once, so dropping a held company from the list would revoke it
        // behind the distributor's back on their next save.
        shadeRepository.deleteAll(shadeRepository.findByBrandSlugOrderByPopularityAsc(brands[0].getSlug()));

        MvcResult res = mockMvc.perform(get("/api/hierarchy/retailers/" + orgId + "/brands")
                        .header("Authorization", "Bearer " + distToken))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(slugsIn(res, "slug")).contains("testco-paints");
        com.fasterxml.jackson.databind.JsonNode body =
                objectMapper.readTree(res.getResponse().getContentAsString());
        body.forEach(node -> {
            if (node.path("slug").asText().equals("testco-paints")) {
                assertThat(node.path("assigned").asBoolean()).isTrue();
            }
        });
    }

    // ── Scoping ───────────────────────────────────────────────────────────

    @Test
    void a_distributor_cannot_touch_a_shop_outside_its_network() throws Exception {
        seedCatalogue();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House"}"""))
                .andExpect(status().isCreated());
        String orgId = shopOrgId("shop@example.com");

        // A second distributor, with no link to that shop.
        String adminToken = tokenFor("root@example.com", "password123");
        mockMvc.perform(post("/api/admin/distributors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Rival","email":"rival@example.com","password":"password123",
                                 "companyName":"Rival Trade","city":"Pune","state":"Maharashtra"}"""))
                .andExpect(status().isCreated());
        String rivalToken = tokenFor("rival@example.com", "password123");

        mockMvc.perform(get("/api/hierarchy/retailers/" + orgId + "/features")
                        .header("Authorization", "Bearer " + rivalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/hierarchy/retailers/" + orgId + "/features")
                        .header("Authorization", "Bearer " + rivalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"features":["STUDIO"],"unrestricted":false}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_shop_cannot_widen_its_own_access() throws Exception {
        seedCatalogue();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House",
                                 "features":["CATALOGUE"],"featuresUnrestricted":false}"""))
                .andExpect(status().isCreated());
        String orgId = shopOrgId("shop@example.com");

        mockMvc.perform(put("/api/hierarchy/retailers/" + orgId + "/features")
                        .header("Authorization", "Bearer " + tokenFor("shop@example.com", "password123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"features":[],"unrestricted":true}"""))
                .andExpect(status().isForbidden());
    }

    // ── The grant is enforced by the API, not only by the navigation ──────

    /**
     * A revoked page must actually close the endpoints behind it.
     *
     * The grant was written, stored, read back and used to hide navigation tabs — and
     * enforced nowhere. {@code FeatureAccessService.assertFeature} had no callers at all,
     * so a shop whose distributor had switched the Customer portal off could still POST
     * an access code straight to the API and issue codes all day; revoking the page hid a
     * tab and nothing more. The frontend's own comment claimed the backend enforced this
     * on every endpoint, which made the gap easy to keep believing.
     */
    @Test
    void a_revoked_page_closes_its_endpoints_not_just_its_nav_tab() throws Exception {
        seedCatalogue();
        String distToken = seedDistributor();
        // A shop granted the Studio only — no Customer portal, no Products.
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House",
                                 "features":["STUDIO"],"featuresUnrestricted":false}"""))
                .andExpect(status().isCreated());
        String orgId = shopOrgId("shop@example.com");
        String shopToken = tokenFor("shop@example.com", "password123");

        // Issuing a customer code is the Customer portal, which they were not granted.
        mockMvc.perform(post("/api/organizations/" + orgId + "/access-codes")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Walk-in\",\"projectQuota\":1}"))
                .andExpect(status().isForbidden());

        // So is listing them, and the shop's product listings are their own page.
        mockMvc.perform(get("/api/organizations/" + orgId + "/access-codes")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/organizations/" + orgId + "/products")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isForbidden());

        // …while the page they WERE granted still answers, and so does the account's own
        // access lookup — the call the navigation is built from must never be gated, or a
        // restricted shop cannot render a nav at all.
        mockMvc.perform(get("/api/hierarchy/my-access")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featuresRestricted").value(true));
        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk());
    }

    /** An unrestricted shop is untouched by the guard, and so is the distributor above it. */
    @Test
    void the_page_guard_does_not_bite_an_unrestricted_shop_or_its_distributor() throws Exception {
        seedCatalogue();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House","featuresUnrestricted":true}"""))
                .andExpect(status().isCreated());
        String orgId = shopOrgId("shop@example.com");

        mockMvc.perform(get("/api/organizations/" + orgId + "/access-codes")
                        .header("Authorization", "Bearer " + tokenFor("shop@example.com", "password123")))
                .andExpect(status().isOk());

        // The distributor holds no shop org of their own, so the guard is a no-op for
        // them — they are the one doing the granting.
        mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + distToken))
                .andExpect(status().isOk());
    }

    // ── The plan's own company cap ────────────────────────────────────────
    //
    // A second limit sits on top of the distributor's: the free tier carries ONE paint
    // company. The catalogue is the thing besides colour matching that is worth money
    // without a project behind it, so a free counter gets a shop it can run end to end
    // — not one it can sell four brands from.

    /**
     * A shop its distributor has not restricted at all is still capped by its own plan,
     * to the free tier's company. This is the case a plain intersection gets wrong: no
     * assignment rows means "no distributor limit", which must not read as "everything".
     */
    @Test
    void a_free_shop_carries_one_company_even_when_its_distributor_restricts_nothing() throws Exception {
        seedCatalogue();
        seedAsianPaints();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House"}"""))
                .andExpect(status().isCreated());

        MvcResult mine = mockMvc.perform(get("/api/shades/mine")
                        .header("Authorization", "Bearer " + tokenFor("shop@example.com", "password123")))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(mine, "brandSlug")).containsOnly("asian-paints");
    }

    /** Subscribing opens the rest of the catalogue to the very same shop. */
    @Test
    void paying_lifts_the_company_cap() throws Exception {
        seedCatalogue();
        seedAsianPaints();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House"}"""))
                .andExpect(status().isCreated());
        putShopOnAPaidPlan("shop@example.com");

        MvcResult mine = mockMvc.perform(get("/api/shades/mine")
                        .header("Authorization", "Bearer " + tokenFor("shop@example.com", "password123")))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(mine, "brandSlug"))
                .contains("testco-paints", "rivalco-paints", "asian-paints");
    }

    /**
     * A free shop whose distributor never assigned it Asian Paints is capped at one of
     * the companies it DOES carry — not left with an empty catalogue over a company it
     * was never given. Still one company, which is what the tier says.
     */
    @Test
    void a_free_shop_without_asian_paints_keeps_one_of_the_companies_it_does_carry() throws Exception {
        Brand[] brands = seedCatalogue();
        seedAsianPaints();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House",
                                 "brandIds":[%d,%d],"brandsUnrestricted":false}"""
                                .formatted(brands[0].getId(), brands[1].getId())))
                .andExpect(status().isCreated());

        MvcResult mine = mockMvc.perform(get("/api/shades/mine")
                        .header("Authorization", "Bearer " + tokenFor("shop@example.com", "password123")))
                .andExpect(status().isOk()).andReturn();
        // Exactly one company, drawn from what the distributor assigned — never the
        // free tier's own, which this shop does not carry.
        assertThat(java.util.Set.copyOf(slugsIn(mine, "brandSlug")))
                .hasSize(1)
                .isSubsetOf(java.util.Set.of("testco-paints", "rivalco-paints"));
    }

    /**
     * The cap reaches the shop's customers too. A walk-in redeeming a free shop's code
     * gets that shop's one company — the code cannot hand out what the shop itself may
     * not offer, and the plan limit is applied in the same place the distributor's is.
     */
    @Test
    void a_free_shops_customer_inherits_the_single_company() throws Exception {
        seedCatalogue();
        seedAsianPaints();
        String distToken = seedDistributor();
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paint House"}"""))
                .andExpect(status().isCreated());
        String orgId = shopOrgId("shop@example.com");

        MvcResult issued = mockMvc.perform(post("/api/organizations/" + orgId + "/access-codes")
                        .header("Authorization", "Bearer " + tokenFor("shop@example.com", "password123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Anjali\",\"projectQuota\":1}"))
                .andExpect(status().isCreated()).andReturn();
        String code = objectMapper.readTree(issued.getResponse().getContentAsString())
                .path("code").asText();

        MvcResult redeemed = mockMvc.perform(post("/api/access-codes/redeem-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String customerToken = objectMapper.readTree(redeemed.getResponse().getContentAsString())
                .path("accessToken").asText();

        MvcResult customerView = mockMvc.perform(get("/api/shades/mine")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(slugsIn(customerView, "brandSlug")).containsOnly("asian-paints");
    }
}
