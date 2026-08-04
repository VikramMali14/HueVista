package com.gridstore.huevista.hierarchy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.DistributorRetailerLinkRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.painter.model.PainterLinkStatus;
import com.gridstore.huevista.painter.repository.PainterRetailerLinkRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the account hierarchy: admin → distributor → retailer
 * → painter provisioning, the auto-links between levels, and the role-scoped
 * network report.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class HierarchyFlowIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DistributorRetailerLinkRepository distributorLinkRepository;
    @Autowired PainterRetailerLinkRepository painterLinkRepository;
    @Autowired CustomerAccessCodeRepository accessCodeRepository;
    @Autowired BrandRepository brandRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String tokenFor(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(login.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }

    private User seedAdmin() {
        return userRepository.save(User.builder()
                .name("Root Admin").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
    }

    @Test
    void full_chain_admin_distributor_retailer_painter() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");

        // Admin creates a distributor.
        mockMvc.perform(post("/api/admin/distributors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Arun","email":"dist@example.com","password":"password123",
                                 "companyName":"Shetty Trade","city":"Hubli","state":"Karnataka"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("DISTRIBUTOR"));

        User distributor = userRepository.findByEmail("dist@example.com").orElseThrow();
        assertThat(distributor.getCreatedById()).isNotNull();
        assertThat(organizationRepository.findByOwnerIdAndType(distributor.getId(), OrgType.DISTRIBUTOR)).hasSize(1);

        // Distributor creates a retailer (auto-linked to the distributor).
        String distToken = tokenFor("dist@example.com", "password123");
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paints","city":"Pune","state":"Maharashtra"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("RETAILER"));

        User retailer = userRepository.findByEmail("shop@example.com").orElseThrow();
        assertThat(retailer.getCreatedById()).isEqualTo(distributor.getId());
        Organization distOrg = organizationRepository.findByOwnerIdAndType(distributor.getId(), OrgType.DISTRIBUTOR).get(0);
        Organization retailerOrg = organizationRepository.findByOwnerIdAndType(retailer.getId(), OrgType.RETAILER).get(0);
        assertThat(distributorLinkRepository.existsByDistributorIdAndRetailerId(distOrg.getId(), retailerOrg.getId())).isTrue();

        // Retailer creates a painter (auto-linked ACTIVE to the shop).
        String shopToken = tokenFor("shop@example.com", "password123");
        mockMvc.perform(post("/api/hierarchy/painters")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Santosh","email":"painter@example.com","password":"password123",
                                 "phone":"+919156883402"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PAINTER"));

        User painter = userRepository.findByEmail("painter@example.com").orElseThrow();
        assertThat(painter.getCreatedById()).isEqualTo(retailer.getId());
        assertThat(painterLinkRepository.findByRetailerIdAndStatus(retailerOrg.getId(), PainterLinkStatus.ACTIVE)).hasSize(1);

        // Admin network report sees the whole chain.
        MvcResult adminReport = mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerRole").value("ADMIN"))
                .andReturn();
        JsonNode admin = objectMapper.readTree(adminReport.getResponse().getContentAsString());
        assertThat(admin.get("totals").get("distributors").asLong()).isEqualTo(1);
        assertThat(admin.get("totals").get("retailers").asLong()).isEqualTo(1);
        assertThat(admin.get("totals").get("painters").asLong()).isEqualTo(1);
        // Distributor root → retailer child → painter grandchild.
        JsonNode distNode = admin.get("roots").get(0);
        assertThat(distNode.get("role").asText()).isEqualTo("DISTRIBUTOR");
        assertThat(distNode.get("retailerCount").asLong()).isEqualTo(1);
        assertThat(distNode.get("painterCount").asLong()).isEqualTo(1);
        JsonNode retailerNode = distNode.get("children").get(0);
        assertThat(retailerNode.get("role").asText()).isEqualTo("RETAILER");
        assertThat(retailerNode.get("children").get(0).get("role").asText()).isEqualTo("PAINTER");

        // Distributor report is scoped to their own subtree.
        mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + distToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerRole").value("DISTRIBUTOR"))
                .andExpect(jsonPath("$.totals.retailers").value(1))
                .andExpect(jsonPath("$.totals.painters").value(1))
                .andExpect(jsonPath("$.roots[0].children[0].role").value("RETAILER"));

        // Retailer report shows their painter roster.
        mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerRole").value("RETAILER"))
                .andExpect(jsonPath("$.totals.painters").value(1))
                .andExpect(jsonPath("$.roots[0].children[0].role").value("PAINTER"));
    }

    /**
     * An admin who names no distributor gets the house one, not a dangling shop.
     * Shops used to be creatable with no distributor at all — outside every downline
     * and answerable to nobody — so "none" now means "ours" and the tree stays whole.
     */
    @Test
    void admin_created_retailer_lands_under_the_house_distributor() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");

        mockMvc.perform(post("/api/admin/retailers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Direct Owner","email":"direct@example.com","password":"password123",
                                 "shopName":"Direct Paints"}"""))
                .andExpect(status().isCreated());

        User retailer = userRepository.findByEmail("direct@example.com").orElseThrow();
        assertThat(retailer.getCreatedById()).isNotNull(); // the admin
        assertThat(retailer.getRole()).isEqualTo(UserRole.RETAILER);

        Organization house = organizationRepository.findBySlug("huevista-direct").orElseThrow();
        Organization retailerOrg =
                organizationRepository.findByOwnerIdAndType(retailer.getId(), OrgType.RETAILER).get(0);
        assertThat(distributorLinkRepository
                .existsByDistributorIdAndRetailerId(house.getId(), retailerOrg.getId())).isTrue();

        mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                // The house org is a distributor NODE but not a distributor ACCOUNT,
                // which is why the total stays at zero.
                .andExpect(jsonPath("$.roots[0].role").value("DISTRIBUTOR"))
                .andExpect(jsonPath("$.roots[0].house").value(true))
                .andExpect(jsonPath("$.roots[0].children[0].role").value("RETAILER"))
                .andExpect(jsonPath("$.totals.distributors").value(0))
                .andExpect(jsonPath("$.totals.retailers").value(1));
    }

    /** An admin can file a new shop under any distributor they choose. */
    @Test
    void admin_can_choose_the_distributor_a_new_shop_belongs_under() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");

        mockMvc.perform(post("/api/admin/distributors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dist Owner","email":"dist@example.com","password":"password123",
                                 "companyName":"Western Paints Co","city":"Pune","state":"Maharashtra"}"""))
                .andExpect(status().isCreated());
        User distributor = userRepository.findByEmail("dist@example.com").orElseThrow();
        Organization distOrg =
                organizationRepository.findByOwnerIdAndType(distributor.getId(), OrgType.DISTRIBUTOR).get(0);

        // The picker offers the house org first, then real distributors.
        mockMvc.perform(get("/api/admin/distributors").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].house").value(true))
                .andExpect(jsonPath("$[1].name").value("Western Paints Co"));

        mockMvc.perform(post("/api/admin/retailers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"chosen@example.com","password":"password123",
                                 "shopName":"Chosen Paints","distributorOrgId":"%s"}""".formatted(distOrg.getId())))
                .andExpect(status().isCreated());

        User retailer = userRepository.findByEmail("chosen@example.com").orElseThrow();
        Organization retailerOrg =
                organizationRepository.findByOwnerIdAndType(retailer.getId(), OrgType.RETAILER).get(0);
        assertThat(distributorLinkRepository
                .existsByDistributorIdAndRetailerId(distOrg.getId(), retailerOrg.getId())).isTrue();
    }

    @Test
    void distributor_cannot_create_painters() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");
        mockMvc.perform(post("/api/admin/distributors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Arun","email":"dist2@example.com","password":"password123",
                                 "companyName":"Shetty Trade"}"""))
                .andExpect(status().isCreated());
        String distToken = tokenFor("dist2@example.com", "password123");

        // /painters is RETAILER-only — a distributor is forbidden.
        mockMvc.perform(post("/api/hierarchy/painters")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"x@example.com","password":"password123"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void non_privileged_user_cannot_read_network_report() throws Exception {
        // A plain customer (public signup) has no downline and no access.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cust","email":"cust@example.com","password":"password123"}"""))
                .andExpect(status().isCreated());
        String custToken = tokenFor("cust@example.com", "password123");

        mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + custToken))
                .andExpect(status().isForbidden());
    }

    /**
     * The report math: one distributor with two shops (2 + 1 painters) and
     * access codes (3 issued/2 redeemed at shop A, 1 issued/0 redeemed at shop B).
     * Verifies the rollups add up at every level.
     */
    @Test
    void report_rollups_are_correct_across_two_shops() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");
        createDistributor(adminToken, "Big Dist", "bigdist@example.com");
        String distToken = tokenFor("bigdist@example.com", "password123");

        // Two shops under the distributor.
        createRetailer(distToken, "Owner A", "shopa@example.com", "Shop A");
        createRetailer(distToken, "Owner B", "shopb@example.com", "Shop B");
        String shopAToken = tokenFor("shopa@example.com", "password123");
        String shopBToken = tokenFor("shopb@example.com", "password123");

        // Shop A: two painters; Shop B: one painter.
        createPainter(shopAToken, "P A1", "pa1@example.com");
        createPainter(shopAToken, "P A2", "pa2@example.com");
        createPainter(shopBToken, "P B1", "pb1@example.com");

        // Access codes: shop A 3 issued / 2 redeemed, shop B 1 issued / 0 redeemed.
        Organization shopAOrg = orgOf("shopa@example.com");
        Organization shopBOrg = orgOf("shopb@example.com");
        seedCode(shopAOrg, "CODEAA01", true);
        seedCode(shopAOrg, "CODEAA02", true);
        seedCode(shopAOrg, "CODEAA03", false);
        seedCode(shopBOrg, "CODEBB01", false);

        // Distributor report: 2 shops, 3 painters, 4 codes issued, 2 redeemed.
        JsonNode dist = report(distToken);
        assertThat(dist.get("viewerRole").asText()).isEqualTo("DISTRIBUTOR");
        assertThat(dist.get("totals").get("retailers").asLong()).isEqualTo(2);
        assertThat(dist.get("totals").get("painters").asLong()).isEqualTo(3);
        assertThat(dist.get("totals").get("codesIssued").asLong()).isEqualTo(4);
        assertThat(dist.get("totals").get("codesRedeemed").asLong()).isEqualTo(2);
        JsonNode distRoot = dist.get("roots").get(0);
        assertThat(distRoot.get("retailerCount").asLong()).isEqualTo(2);
        assertThat(distRoot.get("painterCount").asLong()).isEqualTo(3);
        assertThat(distRoot.get("children")).hasSize(2);

        // Shop A retailer report: 2 painters, 3 codes issued, 2 redeemed.
        JsonNode shopA = report(shopAToken);
        assertThat(shopA.get("viewerRole").asText()).isEqualTo("RETAILER");
        assertThat(shopA.get("totals").get("painters").asLong()).isEqualTo(2);
        assertThat(shopA.get("totals").get("codesIssued").asLong()).isEqualTo(3);
        assertThat(shopA.get("totals").get("codesRedeemed").asLong()).isEqualTo(2);
        assertThat(shopA.get("roots").get(0).get("children")).hasSize(2); // the two painters

        // Admin totals: 1 distributor, 2 retailers, 3 painters, 4/2 codes.
        JsonNode admin = report(adminToken);
        assertThat(admin.get("totals").get("distributors").asLong()).isEqualTo(1);
        assertThat(admin.get("totals").get("retailers").asLong()).isEqualTo(2);
        assertThat(admin.get("totals").get("painters").asLong()).isEqualTo(3);
        assertThat(admin.get("totals").get("codesIssued").asLong()).isEqualTo(4);
        assertThat(admin.get("totals").get("codesRedeemed").asLong()).isEqualTo(2);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void createDistributor(String adminToken, String company, String email) throws Exception {
        mockMvc.perform(post("/api/admin/distributors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + company + "\",\"email\":\"" + email
                                + "\",\"password\":\"password123\",\"companyName\":\"" + company + "\"}"))
                .andExpect(status().isCreated());
    }

    private void createRetailer(String token, String owner, String email, String shopName) throws Exception {
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + owner + "\",\"email\":\"" + email
                                + "\",\"password\":\"password123\",\"shopName\":\"" + shopName + "\"}"))
                .andExpect(status().isCreated());
    }

    private void createPainter(String token, String name, String email) throws Exception {
        mockMvc.perform(post("/api/hierarchy/painters")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
    }

    // ── A shop always has exactly one distributor ─────────────────────────

    /**
     * Leaving a distributor is a change of distributor, not the absence of one.
     *
     * Unlinking used to delete the row and stop, which put the shop exactly where
     * creation no longer allows: outside every downline, answerable to nobody.
     */
    @Test
    void unlinking_moves_the_shop_to_the_house_distributor_rather_than_orphaning_it() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");
        createDistributor(adminToken, "Shetty Trade", "dist@example.com");
        String distToken = tokenFor("dist@example.com", "password123");
        createRetailer(distToken, "Priya", "shop@example.com", "Mehta Paints");

        Organization distOrg = distributorOrgOf("dist@example.com");
        Organization shopOrg = orgOf("shop@example.com");

        mockMvc.perform(delete("/api/organizations/" + distOrg.getId() + "/retailers/" + shopOrg.getId())
                        .header("Authorization", "Bearer " + distToken))
                .andExpect(status().isNoContent());

        Organization house = organizationRepository.findBySlug("huevista-direct").orElseThrow();
        assertThat(distributorLinkRepository.findByRetailerId(shopOrg.getId()))
                .singleElement()
                .satisfies(l -> assertThat(l.getDistributor().getId()).isEqualTo(house.getId()));
    }

    /** The house distributor is the fallback, so there is nowhere to fall back to. */
    @Test
    void a_shop_cannot_unlink_from_the_house_distributor() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");
        mockMvc.perform(post("/api/admin/retailers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Direct Owner","email":"direct@example.com","password":"password123",
                                 "shopName":"Direct Paints"}"""))
                .andExpect(status().isCreated());

        Organization house = organizationRepository.findBySlug("huevista-direct").orElseThrow();
        Organization shopOrg = orgOf("direct@example.com");

        mockMvc.perform(delete("/api/organizations/" + house.getId() + "/retailers/" + shopOrg.getId())
                        .header("Authorization", "Bearer " + tokenFor("direct@example.com", "password123")))
                .andExpect(status().isConflict());
        assertThat(distributorLinkRepository.findByRetailerId(shopOrg.getId())).hasSize(1);
    }

    /**
     * A shop filed under the wrong distributor was stuck there: the distributor-facing
     * link endpoint demands ownership of both organizations, which an admin never has.
     */
    @Test
    void an_admin_can_move_a_shop_between_distributors() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");
        createDistributor(adminToken, "First Trade", "first@example.com");
        createDistributor(adminToken, "Second Trade", "second@example.com");
        String firstToken = tokenFor("first@example.com", "password123");
        createRetailer(firstToken, "Priya", "shop@example.com", "Mehta Paints");

        Organization firstOrg = distributorOrgOf("first@example.com");
        Organization secondOrg = distributorOrgOf("second@example.com");
        Organization shopOrg = orgOf("shop@example.com");

        // The first distributor limits the shop to a single company.
        Brand brand = brandRepository.save(Brand.builder().name("Test Colour Co").slug("test-colour-co").build());
        mockMvc.perform(put("/api/hierarchy/retailers/" + shopOrg.getId() + "/brands")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandIds\":[" + brand.getId() + "],\"unrestricted\":false}"))
                .andExpect(status().isOk());
        assertThat(organizationRepository.findById(shopOrg.getId()).orElseThrow().isBrandsRestricted()).isTrue();

        mockMvc.perform(put("/api/admin/retailers/" + shopOrg.getId() + "/distributor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"distributorOrgId\":\"" + secondOrg.getId() + "\"}"))
                .andExpect(status().isOk());

        // Exactly one link, and it is the new one — not a second row alongside the old.
        assertThat(distributorLinkRepository.findByRetailerId(shopOrg.getId()))
                .singleElement()
                .satisfies(l -> assertThat(l.getDistributor().getId()).isEqualTo(secondOrg.getId()));
        assertThat(distributorLinkRepository.findByDistributorId(firstOrg.getId())).isEmpty();

        // The old distributor's restriction went with them — it was theirs to make, and
        // the new distributor never chose it.
        assertThat(organizationRepository.findById(shopOrg.getId()).orElseThrow().isBrandsRestricted()).isFalse();
    }

    /** Blank means the house distributor, the same as everywhere else. */
    @Test
    void moving_a_shop_with_no_distributor_named_files_it_under_the_house_one() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");
        createDistributor(adminToken, "Shetty Trade", "dist@example.com");
        createRetailer(tokenFor("dist@example.com", "password123"), "Priya", "shop@example.com", "Mehta Paints");
        Organization shopOrg = orgOf("shop@example.com");

        mockMvc.perform(put("/api/admin/retailers/" + shopOrg.getId() + "/distributor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Organization house = organizationRepository.findBySlug("huevista-direct").orElseThrow();
        assertThat(distributorLinkRepository.findByRetailerId(shopOrg.getId()))
                .singleElement()
                .satisfies(l -> assertThat(l.getDistributor().getId()).isEqualTo(house.getId()));
    }

    /** Pressing it twice must not clear the shop's grants a second time. */
    @Test
    void moving_a_shop_to_the_distributor_it_already_has_changes_nothing() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");
        createDistributor(adminToken, "Shetty Trade", "dist@example.com");
        String distToken = tokenFor("dist@example.com", "password123");
        createRetailer(distToken, "Priya", "shop@example.com", "Mehta Paints");
        Organization distOrg = distributorOrgOf("dist@example.com");
        Organization shopOrg = orgOf("shop@example.com");

        Brand brand = brandRepository.save(Brand.builder().name("Test Colour Co").slug("test-colour-co").build());
        mockMvc.perform(put("/api/hierarchy/retailers/" + shopOrg.getId() + "/brands")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandIds\":[" + brand.getId() + "],\"unrestricted\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/retailers/" + shopOrg.getId() + "/distributor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"distributorOrgId\":\"" + distOrg.getId() + "\"}"))
                .andExpect(status().isOk());

        assertThat(distributorLinkRepository.findByRetailerId(shopOrg.getId())).hasSize(1);
        assertThat(organizationRepository.findById(shopOrg.getId()).orElseThrow().isBrandsRestricted()).isTrue();
    }

    private JsonNode report(String token) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private Organization distributorOrgOf(String email) {
        User u = userRepository.findByEmail(email).orElseThrow();
        return organizationRepository.findByOwnerIdAndType(u.getId(), OrgType.DISTRIBUTOR).get(0);
    }

    private Organization orgOf(String email) {
        User u = userRepository.findByEmail(email).orElseThrow();
        return organizationRepository.findByOwnerIdAndType(u.getId(), OrgType.RETAILER).get(0);
    }

    private void seedCode(Organization org, String code, boolean redeemed) {
        accessCodeRepository.save(CustomerAccessCode.builder()
                .organization(org)
                .code(code)
                .validDays(7)
                .expiresAt(java.time.LocalDateTime.now().plusDays(7))
                .usedAt(redeemed ? java.time.LocalDateTime.now() : null)
                .guestRedeemed(redeemed)
                .build());
    }
}
