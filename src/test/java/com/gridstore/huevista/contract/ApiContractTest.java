package com.gridstore.huevista.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-contract tripwire.
 *
 * The frontend's src/lib/types.ts mirrors these DTOs BY HAND — that silent drift
 * (a renamed field here, a shape change there) is exactly how earlier critical
 * bugs crept in. Each test pins a DTO's property set as the generated OpenAPI
 * spec reports it; renaming, removing or adding a field fails the pin so the
 * change is forced to be deliberate.
 *
 * WHEN A PIN FAILS: you changed a response shape the frontend depends on.
 * 1. Update the pinned set here in the SAME commit, and
 * 2. Update the matching interface in HueVistaFrontEnd/src/lib/types.ts (and its
 *    demo fixtures under src/lib/demo/) before merging either side.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(locations = "classpath:application-test.properties",
        properties = {
                // The spec is generated on demand for this test only; Swagger UI stays off.
                "springdoc.api-docs.enabled=true",
                "springdoc.swagger-ui.enabled=false",
        })
class ApiContractTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private JsonNode schemas;

    @BeforeAll
    void loadSpec() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        schemas = objectMapper.readTree(spec).path("components").path("schemas");
        assertThat(schemas.isMissingNode()).as("OpenAPI spec has no schemas").isFalse();
    }

    private List<String> propsOf(String schema) {
        JsonNode node = schemas.path(schema).path("properties");
        assertThat(node.isMissingNode())
                .as("Schema %s missing from the OpenAPI spec — was the DTO renamed? "
                        + "The frontend types.ts mirrors it by name.", schema)
                .isFalse();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---- Studio core -------------------------------------------------------

    @Test
    void project_response_matches_frontend_ProjectDetail() {
        assertThat(propsOf("ProjectResponse")).containsExactlyInAnyOrder(
                "id", "name", "roomType", "notes", "status", "imageId", "imageUrl",
                // The scene the pipeline RAN this project as. Only the project can
                // carry it: a guest upload comes back UNKNOWN and is classified later.
                "imageType",
                "cleanedImageUrl", "rawMaskUrl", "failureReason",
                // Which half of a failed run gave up, so the studio can offer the
                // report with the right problem already ticked.
                "failureStage",
                "maskMode", "regions",
                "hasShareLink", "shareExpiresAt", "sharedBrands", "sentToShopAt",
                "createdAt", "updatedAt",
                // Shared view only: how the issuing shop presents a colour. The share
                // viewer has no session, so it travels with the project.
                "shadeCodeScheme",
                // Closing: when the job finished, how many colour boards it has handed
                // over of the ones it gets, and what is left of its AI render allowance.
                "closedAt", "boardsUsed", "boardsAllowed",
                "rendersAllowed", "rendersUsed", "renderPricePaise",
                // Access: the studio disables the palette on readOnly rather than letting
                // the user paint and then fail on autosave.
                "readOnly", "readOnlyReason", "accessExpiresAt",
                // Both rails, quoted from the PROJECT: a lapsed window and a closed
                // project are different purchases at different prices.
                "reopenPricePoints", "reopenPricePaise");
    }

    @Test
    void project_summary_matches_frontend_ProjectSummary() {
        assertThat(propsOf("ProjectSummaryResponse")).containsExactlyInAnyOrder(
                "id", "name", "status", "imageId", "imageUrl", "cleanedImageUrl",
                "regionCount", "hasShareLink", "createdAt", "updatedAt",
                // Dashboard filtering: OWN vs CUSTOMER rooms, and who each one belongs to.
                "source", "customerName", "accessCode", "accessCodeId",
                "readOnly", "accessExpiresAt",
                // A closed room is finished, not merely locked — the dashboard badges the
                // two differently.
                "closedAt");
    }

    @Test
    void project_purchase_options_match_frontend() {
        // Two rails, and the tier both were read off — the price of a project falls with
        // the buyer's plan, so it is quoted per account rather than stated as a constant.
        assertThat(propsOf("ProjectPurchaseOptionsResponse")).containsExactlyInAnyOrder(
                "subscribed", "pricingPlan", "projectPricePoints", "projectPricePaise",
                // The bundle is quoted beside the single price, never instead of it: the
                // saving is only legible next to the thing it discounts.
                "bundleCredits", "bundlePricePaise",
                "reopenPricePoints", "reopenPricePaise",
                // Whether the points rail is open to this account at all, separately from
                // whether the balance covers it: a CUSTOMER cannot hold points, so the UI
                // needs to drop that button rather than offer one the server refuses.
                "pointsBalance", "pointsEligible", "validDays", "availableCredits");
    }

    @Test
    void region_response_matches_frontend_RegionDetail() {
        assertThat(propsOf("RegionResponse")).containsExactlyInAnyOrder(
                "id", "label", "category", "maskData", "maskUrl",
                "appliedShadeCode", "appliedHexCode", "displayOrder", "manual");
    }

    @Test
    void image_response_matches_frontend_UploadedImage() {
        assertThat(propsOf("ImageResponse")).containsExactlyInAnyOrder(
                "imageId", "imageUrl", "originalFilename", "imageType", "fileSize", "uploadedAt");
    }

    @Test
    void share_response_matches_frontend_ShareLink() {
        assertThat(propsOf("ShareResponse")).containsExactlyInAnyOrder(
                "shareUrl", "shareToken", "expiresAt");
    }

    @Test
    void mask_report_shapes_match_frontend_MaskReport() {
        // One DTO serves two audiences: the reporter gets the first line back as a
        // receipt, the admin queue reads the rest. Both are pinned together because
        // both are mirrored by one interface in types.ts.
        assertThat(propsOf("MaskReportResponse")).containsExactlyInAnyOrder(
                "id", "issues", "note", "status", "createdAt",
                "projectId", "projectName",
                "reporterName", "reporterEmail", "reporterRole", "shopName",
                // The reported RUN, snapshotted — re-running segmentation overwrites
                // every one of these on the project itself.
                "projectStatus", "maskMode", "regionCount", "hadCleanedImage",
                // Which stage the reported run failed at, and what it told the user.
                // Null on a report against a run that believed it had succeeded.
                "failureStage", "failureReason",
                "adminNote", "resolvedByName", "resolvedAt", "updatedAt");
        assertThat(propsOf("CreateMaskReportRequest")).containsExactlyInAnyOrder("issues", "note");
        assertThat(propsOf("UpdateMaskReportRequest")).containsExactlyInAnyOrder("status", "adminNote");
    }

    // ---- Claude recommendations (AI Suggest tab) ---------------------------

    @Test
    void recommendation_shapes_match_frontend_Ai_types() {
        assertThat(propsOf("RecommendationResponse")).containsExactlyInAnyOrder(
                "projectId", "imageType", "combinations");
        assertThat(propsOf("ColorCombo")).containsExactlyInAnyOrder(
                "name", "rationale", "primaryHex", "primaryShade",
                "accentHex", "accentShade", "trimHex", "trimShade");
        assertThat(propsOf("MatchedShade")).containsExactlyInAnyOrder(
                "id", "shadeCode", "name", "hexCode", "brand",
                "shadeFamily", "aiDescription", "deltaE");
    }

    // ---- Auth + guest flow --------------------------------------------------

    @Test
    void auth_response_matches_frontend_AuthResponse() {
        assertThat(propsOf("AuthResponse")).containsExactlyInAnyOrder(
                "accessToken", "refreshToken", "tokenType", "expiresIn", "user",
                "twoFactorRequired");
    }

    @Test
    void guest_redeem_response_matches_frontend_GuestSession() {
        assertThat(propsOf("GuestRedeemResponse")).containsExactlyInAnyOrder(
                "guestToken", "code", "shopName", "validDays", "expiresAt", "allowedBrands");
    }
}
