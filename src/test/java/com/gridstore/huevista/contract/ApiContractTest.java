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
                "maskMode",
                // The run kept its cleaned canvas but detection found no walls. NOT a
                // failure — the project opens and works — so the studio needs its own
                // flag to know to ask for hand-marked walls.
                "autoMaskFailed",
                // What to SAY about it, written once by the backend so the studio, the
                // share view and the kiosk stop each carrying their own paraphrase.
                "autoMaskNotice",
                // The running commentary while a run is in flight. The pipeline walks a
                // chain of models and hands over whenever one is busy; without this the
                // studio shows one motionless spinner for minutes and a working run is
                // indistinguishable from a dead one.
                "aiProgressNote",
                // Which image models an admin pinned this run to, null for the
                // configured ones. The mask viewer names them beside the canvas: a
                // model comparison nobody can attribute afterwards was not one.
                "cleanModel", "maskModel",
                // What a closer look at the photo found, when an admin asked for one,
                // and which prompt knobs that run used. Null on every project that never
                // asked — which is every customer project. Carried for the same reason
                // as the two model overrides above: a prompt experiment nobody can
                // attribute afterwards was not one.
                "houseType",
                // The colour the walls are RIGHT NOW. Shown beside the palette as
                // context and never used as a paint colour — the cleaned canvas stays
                // white because the frontend's recolour maths treats it as an
                // illumination map.
                "detectedWallHex", "detectedWallColour", "detectedTrimHex",
                "cleanFurnishing", "cleanAngle",
                "regions",
                "hasShareLink", "shareExpiresAt", "sharedBrands", "sentToShopAt",
                "createdAt", "updatedAt",
                // Shared view only: how the issuing shop presents a colour. The share
                // viewer has no session, so it travels with the project.
                "shadeCodeScheme",
                // Copied off the free library shelf. Changes what the studio may OFFER,
                // not only how it reads: such a room has no board cap, never closes and
                // never lapses, so the close button and the countdown are withheld.
                "fromLibrary",
                // Closing: when the job finished, and how many colour boards it has
                // handed over of the ones it gets. `rendersUsed` counts the AI images the
                // room has made — a count, not an allowance: there is no per-project
                // entitlement to an image and no per-project price for one, because every
                // image is bought with an AI credit from the account's own wallet.
                "closedAt", "boardsUsed", "boardsAllowed", "rendersUsed",
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
                // A library room never lapses and never closes, so neither the expiry
                // line nor the "done" badge belongs on its card.
                "fromLibrary",
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
                // Two codes for the applied colour, and which one a surface may show is
                // the whole point of the pair: the manufacturer's is for shop staff, the
                // HV code goes everywhere else — a customer's screen, a printed board, a
                // forwarded share link — because it names nothing and only a HueVista
                // shop can read it back.
                "appliedShadeCode", "appliedHvCode",
                "appliedHexCode", "displayOrder", "manual");
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
                // Whether the PIPELINE filed this (detection came back empty) or a
                // person did. The queue reads very differently for the two.
                "autoRaised",
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

    /**
     * The kiosk receipt carries a real session, because the walk-in's account is opened
     * for them at the till. If {@code session} ever goes missing from this shape, the
     * customer is paying and then being asked to sign up — which is the queue at the
     * counter this whole flow exists to remove.
     */
    @Test
    void store_checkout_response_matches_frontend_StoreCheckoutResult() {
        assertThat(propsOf("StoreCheckoutResponse")).containsExactlyInAnyOrder(
                "code", "shopName", "validDays", "expiresAt", "amountPaise",
                "session", "accountEmail", "existingAccount");
    }

    @Test
    void guest_merge_response_matches_frontend_GuestMergeResult() {
        assertThat(propsOf("GuestMergeResponse")).containsExactlyInAnyOrder(
                "mergedFromUserId", "projectsMoved", "imagesMoved",
                "projectAllowanceMoved", "aiCreditsMoved", "shopName");
    }
}
