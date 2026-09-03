package com.gridstore.huevista.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.common.ai.AiModelCatalogue;
import com.gridstore.huevista.common.exception.StorageException;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.dto.MaskLabApproach;
import com.gridstore.huevista.project.dto.MaskLabRequest;
import com.gridstore.huevista.project.dto.MaskLabResponse;
import com.gridstore.huevista.project.dto.MaskLabResponse.MaskLabOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A bench for answering one question: is there a better way to produce this
 * product's masks than the one it ships?
 *
 * <p>The pipeline's mask comes from a GENERATIVE model — an image model asked to
 * repaint the photo into flat category colours. That model understands what a
 * wall IS, which is why it is used, but it redraws rather than traces, so every
 * boundary it returns lands a few percent off the surface it describes.
 * {@link MaskAligner} measures that offset and corrects it, and the admin align
 * bench exists for the runs where the correction is not enough. All of that is
 * downstream of one choice: using a model that redraws to do a job about
 * geometry.
 *
 * <p>So this runs the alternatives against the SAME uploaded photograph and
 * hands back what each produced, with the time it took. It writes nothing to any
 * project, spends no credit against anybody's plan, and has no opinion about
 * which approach wins — the point is to make the comparison possible, on real
 * photographs, before anything in the pipeline changes.
 *
 * <h2>On the Replicate calls here</h2>
 *
 * <p>{@link MaskLabApproach#GENERATIVE} goes through {@link ReplicateMaskSegmenter}
 * itself rather than a copy of it. That matters: it is the baseline every other
 * approach is being judged against, and a baseline that is a re-implementation of
 * production is a comparison against something nobody ships.
 *
 * <p>The other two Replicate approaches use the small client below instead of
 * reaching into {@link SegmentationService}'s private prediction plumbing. The
 * duplication is deliberate — this is a diagnostic screen, and wiring it into the
 * internals of the path that serves paying customers would let a change made for
 * the lab's convenience reach them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaskLabService {

    private final StorageService storageService;
    private final ReplicateMaskSegmenter maskSegmenter;
    private final AiModelCatalogue catalogue;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final String SAM2_MODEL = "meta/sam-2";

    /** Longest side the local approaches work at. Big enough that a trim strip
     *  survives, small enough that a flood fill over it is instant. */
    private static final int WORK_DIM = 1600;

    /** Uploads are capped here as well as at the multipart layer: this endpoint
     *  hands its bytes to third parties, and "how big a file can an admin post
     *  to Replicate" should have an answer in this file. */
    private static final long MAX_UPLOAD_BYTES = 12L * 1024 * 1024;

    /** Where a run's images are filed. Not a project, because a lab run belongs
     *  to no room and must never appear in one. */
    private static final String LAB_SCOPE = "mask-lab";

    /** Defaults for {@link MaskLabApproach#PAINTED_SURFACE}. The tolerance is
     *  wide because the clean is told to KEEP each surface's light and shade, so
     *  a white wall runs from highlight to deep shadow and only its lack of
     *  colour is constant. */
    private static final int DEFAULT_TOLERANCE = 46;
    private static final double DEFAULT_MIN_BLOB_SHARE = 0.004;

    /**
     * Runs one approach against one uploaded photograph.
     *
     * @param file  the cleaned canvas — the image the studio would paint, which
     *              is the only image a mask is worth measuring against
     */
    public MaskLabResponse run(MultipartFile file, MaskLabRequest request) {
        byte[] bytes = readUpload(file);
        BufferedImage canvas = decode(bytes);
        String canvasUrl = store(bytes, "canvas.png", "image/png");

        MaskLabApproach approach = request.getApproach() == null
                ? MaskLabApproach.GENERATIVE : request.getApproach();

        long started = System.currentTimeMillis();
        try {
            return switch (approach) {
                case GENERATIVE -> runGenerative(request, canvasUrl, started);
                case PAINTED_SURFACE -> runPaintedSurface(request, canvas, canvasUrl, started);
                case SAM_POINTS -> runSamPoints(request, canvas, canvasUrl, started);
                case CUSTOM_REPLICATE -> runCustomReplicate(request, canvasUrl, started);
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // A lab run failing is information, not an outage — the whole point
            // is to find out that an approach does not work on this photo.
            log.warn("Mask lab {} run failed: {}", approach, e.toString());
            throw new IllegalStateException(
                    "That run did not produce a mask: " + e.getMessage(), e);
        }
    }

    // ─── GENERATIVE ─────────────────────────────────────────────────────────

    private MaskLabResponse runGenerative(MaskLabRequest request, String canvasUrl, long started) {
        String model = request.getModel();
        if (model != null && !model.isBlank()) {
            // The same allow-list every other admin model pin is checked against.
            model = catalogue.resolveOverride(model).orElseThrow(() -> new IllegalArgumentException(
                    "That model is not in the catalogue: " + request.getModel()));
        }
        ImageType scene = "INDOOR".equalsIgnoreCase(request.getScene())
                ? ImageType.INDOOR : ImageType.OUTDOOR;

        Optional<byte[]> mask = maskSegmenter.generateColorCodedMask(canvasUrl, scene, model);
        long ms = System.currentTimeMillis() - started;
        if (mask.isEmpty()) {
            throw new IllegalStateException(
                    "The mask model returned nothing. Either wall detection is switched off in "
                            + "this deployment (replicate.nano-banana.enabled), or every model it "
                            + "asked declined.");
        }
        String url = store(mask.get(), "generative.png", "image/png");
        return new MaskLabResponse(
                MaskLabApproach.GENERATIVE, model, ms, canvasUrl,
                List.of(new MaskLabOutput("Colour-coded mask", url, MaskLabOutput.Kind.COLOUR_CODED)),
                "Redraws the photo, so expect the blocks to sit a little off their surfaces — that "
                        + "offset is what MaskAligner exists to correct.");
    }

    // ─── PAINTED_SURFACE ────────────────────────────────────────────────────

    /**
     * Reads the surfaces the CLEAN already repainted, straight out of the pixels.
     *
     * <p>Costs nothing, takes milliseconds, and cannot drift — the mask comes
     * from the same image the studio paints. Its limit is structural rather than
     * a matter of tuning: the cleaner repaints walls and trim the SAME white on
     * purpose (so the canvas doubles as an illumination map), so this can say
     * where the paintable boundary is and not which side of it is trim.
     *
     * <p>The grey it cannot help catching is the honest part of the result: a
     * concrete road and an overcast sky are also low-chroma, and the blob filter
     * is what stands between this and painting the street. The coverage figures
     * in the note are there so that shows up as a number rather than a surprise.
     */
    private MaskLabResponse runPaintedSurface(MaskLabRequest request, BufferedImage canvas,
                                              String canvasUrl, long started) {
        BufferedImage work = MaskProcessor.downsample(canvas, WORK_DIM);
        int w = work.getWidth(), h = work.getHeight();
        int tolerance = request.getTolerance() == null
                ? DEFAULT_TOLERANCE : Math.max(1, Math.min(160, request.getTolerance()));
        double minBlobShare = request.getMinBlobShare() == null
                ? DEFAULT_MIN_BLOB_SHARE : Math.max(0, Math.min(0.5, request.getMinBlobShare()));

        int[] px = work.getRGB(0, 0, w, h, null, 0, w);
        boolean[] paintable = new boolean[w * h];
        boolean[] doors = new boolean[w * h];
        boolean[] railings = new boolean[w * h];

        int[] doorRgb = hexToRgb(ImageCleanerService.DOOR_LEAF);
        int[] railRgb = hexToRgb(ImageCleanerService.RAILING);

        for (int i = 0; i < px.length; i++) {
            int r = (px[i] >> 16) & 0xff, g = (px[i] >> 8) & 0xff, b = px[i] & 0xff;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            // The repaint is white, but the clean keeps the shading, so what is
            // constant across a wall is the ABSENCE of colour, not the level.
            if (max - min <= tolerance / 2 && max >= 90) {
                paintable[i] = true;
                continue;
            }
            // NEAREST palette colour, not the first one within tolerance. Dark
            // brown and charcoal grey sit close enough that a first-match test
            // hands every railing to the doors — which is not a tuning problem,
            // it is the wrong question: the pixel belongs to whichever colour it
            // is actually closest to.
            int dDoor = dist2(r, g, b, doorRgb);
            int dRail = dist2(r, g, b, railRgb);
            int limit = 3 * tolerance * tolerance;
            if (dDoor <= limit || dRail <= limit) {
                if (dDoor <= dRail) doors[i] = true; else railings[i] = true;
            }
        }

        int before = count(paintable);
        dropSmallBlobs(paintable, w, h, (int) Math.round(minBlobShare * w * h));
        int after = count(paintable);

        List<MaskLabOutput> outputs = new ArrayList<>();
        outputs.add(new MaskLabOutput("Paintable surface (walls + trim)",
                store(toMaskPng(paintable, w, h), "painted-surface.png", "image/png"),
                MaskLabOutput.Kind.BINARY));
        if (count(doors) > 0) {
            outputs.add(new MaskLabOutput("Door leaves",
                    store(toMaskPng(doors, w, h), "doors.png", "image/png"),
                    MaskLabOutput.Kind.BINARY));
        }
        if (count(railings) > 0) {
            outputs.add(new MaskLabOutput("Railings",
                    store(toMaskPng(railings, w, h), "railings.png", "image/png"),
                    MaskLabOutput.Kind.BINARY));
        }

        long ms = System.currentTimeMillis() - started;
        String note = String.format(
                "Paintable %.1f%% of frame (%.1f%% before small blobs were dropped) · doors %.1f%% "
                        + "· railings %.1f%%. Walls and trim are ONE mask here: the clean repaints "
                        + "both the same white on purpose, so colour cannot tell them apart. Check "
                        + "the road and the sky — they are low-chroma too.",
                pct(after, px.length), pct(before, px.length),
                pct(count(doors), px.length), pct(count(railings), px.length));

        return new MaskLabResponse(MaskLabApproach.PAINTED_SURFACE, null, ms, canvasUrl, outputs, note);
    }

    // ─── SAM_POINTS ─────────────────────────────────────────────────────────

    private MaskLabResponse runSamPoints(MaskLabRequest request, BufferedImage canvas,
                                         String canvasUrl, long started) {
        List<List<Double>> points = request.getPoints();
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException(
                    "SAM needs at least one point — it is prompted with positions, not words. "
                            + "Click the surface you want on the image.");
        }
        int w = canvas.getWidth(), h = canvas.getHeight();
        List<List<Double>> pixels = new ArrayList<>();
        for (List<Double> p : points) {
            if (p == null || p.size() < 2) continue;
            pixels.add(List.of(p.get(0) * w, p.get(1) * h));
        }
        if (pixels.isEmpty()) throw new IllegalArgumentException("No usable points were sent.");

        List<Integer> labels = request.getPointLabels();
        if (labels == null || labels.size() != pixels.size()) {
            labels = pixels.stream().map(p -> 1).toList();
        }

        Map<String, Object> input = Map.of(
                "image", canvasUrl,
                "input_points", pixels,
                "input_labels", labels);

        Map<String, Object> result = runPrediction(SAM2_MODEL, input);
        String maskUrl = firstUrl(result.get("output"));
        long ms = System.currentTimeMillis() - started;
        if (maskUrl == null) {
            throw new IllegalStateException("SAM returned no mask for those points.");
        }
        return new MaskLabResponse(
                MaskLabApproach.SAM_POINTS, SAM2_MODEL, ms, canvasUrl,
                List.of(new MaskLabOutput("SAM mask", maskUrl, MaskLabOutput.Kind.BINARY)),
                "Traced from the real pixels, so it is exact — but it is one surface, unnamed. "
                        + "SAM output is sometimes inverted; the studio's own path repairs that.");
    }

    // ─── CUSTOM_REPLICATE ───────────────────────────────────────────────────

    /**
     * Runs any model with a body typed by hand.
     *
     * <p>Deliberately not checked against {@code AiModelCatalogue}: the approach
     * exists to try models nobody has added yet, and an allow-list would make it
     * the one thing it must not be — a list of what has already been decided.
     * ROLE_ADMIN is the guard, and the only thing the model is handed is one
     * uploaded image that the admin chose.
     */
    private MaskLabResponse runCustomReplicate(MaskLabRequest request, String canvasUrl, long started) {
        String model = request.getModel();
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Name the model to run, as owner/name.");
        }
        String template = request.getInputTemplate();
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException(
                    "Give the model's input body as JSON, with {{image}} where the image URL goes.");
        }
        if (!template.contains("{{image}}")) {
            throw new IllegalArgumentException(
                    "The body has no {{image}} placeholder, so the image would never reach the "
                            + "model. Put {{image}} where its image input belongs.");
        }

        Map<String, Object> input;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(
                    template.replace("{{image}}", canvasUrl), Map.class);
            input = parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("That input body is not valid JSON: " + e.getMessage());
        }

        Map<String, Object> result = runPrediction(model.trim(), input);
        long ms = System.currentTimeMillis() - started;

        List<String> urls = allUrls(result.get("output"));
        if (urls.isEmpty()) {
            throw new IllegalStateException(
                    "The model ran but returned no image. Output was: " + brief(result.get("output")));
        }
        List<MaskLabOutput> outputs = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            outputs.add(new MaskLabOutput(
                    urls.size() == 1 ? "Output" : "Output " + (i + 1),
                    urls.get(i), MaskLabOutput.Kind.RAW));
        }
        return new MaskLabResponse(MaskLabApproach.CUSTOM_REPLICATE, model, ms, canvasUrl, outputs,
                urls.size() > 1
                        ? urls.size() + " images came back — a segmenter often returns one per class."
                        : "Shown as returned. Whether it is a mask, a colour map or a visualisation "
                                + "is for you to read.");
    }

    // ─── Replicate ──────────────────────────────────────────────────────────

    /** Create-and-wait against the model endpoint, with the same 60s "Prefer:
     *  wait" the rest of this codebase uses. No version pinning: the lab is for
     *  trying whatever is current. */
    private Map<String, Object> runPrediction(String model, Map<String, Object> input) {
        if (replicateApiToken == null || replicateApiToken.isBlank()) {
            throw new IllegalStateException(
                    "REPLICATE_API_TOKEN is not set in this deployment, so no model can be asked.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);
        headers.set("Prefer", "wait=60");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    REPLICATE_BASE + "/models/" + model + "/predictions",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("input", input), headers),
                    Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            if (body == null) throw new IllegalStateException("Replicate returned an empty body.");
            Object status = body.get("status");
            if ("failed".equals(status) || "canceled".equals(status)) {
                throw new IllegalStateException("Replicate says the run " + status + ": "
                        + brief(body.get("error")));
            }
            if (!"succeeded".equals(status)) {
                throw new IllegalStateException(
                        "The model did not finish inside 60s (status " + status + "). The lab waits "
                                + "once rather than polling — try a smaller image, or a faster model.");
            }
            return body;
        } catch (RestClientException e) {
            // The model name is the usual cause and the usual fix, so say so.
            throw new IllegalStateException(
                    "Replicate refused that call: " + e.getMessage()
                            + " — check the model name is owner/name and that the input body matches "
                            + "the schema on its Replicate page.", e);
        }
    }

    private String firstUrl(Object output) {
        List<String> all = allUrls(output);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Replicate models return a URL, a list of URLs, or an object holding
     *  either. Walk whatever came back and collect anything that looks like an
     *  image URL, because the lab does not know the model's output shape. */
    private List<String> allUrls(Object output) {
        List<String> out = new ArrayList<>();
        collectUrls(output, out, 0);
        return out;
    }

    private void collectUrls(Object node, List<String> out, int depth) {
        if (node == null || depth > 4 || out.size() >= 12) return;
        if (node instanceof String s) {
            if (s.startsWith("http://") || s.startsWith("https://")) out.add(s);
        } else if (node instanceof List<?> list) {
            for (Object o : list) collectUrls(o, out, depth + 1);
        } else if (node instanceof Map<?, ?> map) {
            for (Object o : map.values()) collectUrls(o, out, depth + 1);
        }
    }

    // ─── pixels ─────────────────────────────────────────────────────────────

    /** Squared RGB distance to a palette colour. Squared because nothing here
     *  needs the root — only which of two colours is nearer, and by enough. */
    private static int dist2(int r, int g, int b, int[] target) {
        int dr = r - target[0], dg = g - target[1], db = b - target[2];
        return dr * dr + dg * dg + db * db;
    }

    private static int[] hexToRgb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new int[]{
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16)};
    }

    private static int count(boolean[] bits) {
        int n = 0;
        for (boolean b : bits) if (b) n++;
        return n;
    }

    private static double pct(int n, int total) {
        return total == 0 ? 0 : (n * 100.0) / total;
    }

    /**
     * Clears every connected run of foreground smaller than {@code minPixels}.
     *
     * <p>8-connected, matching {@link MaskProcessor}: a faint JPEG seam along a
     * wall corner should not split one wall into two blobs that both then fall
     * under the threshold. Iterative rather than recursive — a facade fills most
     * of the frame, and a recursive fill over a million-pixel blob overflows the
     * stack.
     */
    private static void dropSmallBlobs(boolean[] bits, int w, int h, int minPixels) {
        if (minPixels <= 1) return;
        boolean[] seen = new boolean[bits.length];
        Deque<Integer> stack = new ArrayDeque<>();
        List<Integer> blob = new ArrayList<>();

        for (int start = 0; start < bits.length; start++) {
            if (!bits[start] || seen[start]) continue;
            blob.clear();
            stack.push(start);
            seen[start] = true;
            while (!stack.isEmpty()) {
                int i = stack.pop();
                blob.add(i);
                int x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int ni = ny * w + nx;
                        if (bits[ni] && !seen[ni]) { seen[ni] = true; stack.push(ni); }
                    }
                }
            }
            if (blob.size() < minPixels) for (int i : blob) bits[i] = false;
        }
    }

    private static byte[] toMaskPng(boolean[] bits, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[w * h];
        for (int i = 0; i < bits.length; i++) px[i] = bits[i] ? 0xFFFFFF : 0x000000;
        img.setRGB(0, 0, w, h, px, 0, w);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new StorageException("Could not encode a lab mask", e);
        }
    }

    // ─── plumbing ───────────────────────────────────────────────────────────

    private byte[] readUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a cleaned image to run against.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("That image is over 12 MB — downscale it first.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("That upload could not be read.");
        }
    }

    private BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) throw new IllegalArgumentException("That file is not an image.");
            return img;
        } catch (IOException e) {
            throw new IllegalArgumentException("That file is not an image.");
        }
    }

    private String store(byte[] bytes, String name, String contentType) {
        try {
            return storageService.getPublicUrl(
                    storageService.store(bytes, LAB_SCOPE, name, contentType));
        } catch (IOException e) {
            throw new StorageException("Could not store a mask lab image", e);
        }
    }

    private static String brief(Object o) {
        if (o == null) return "nothing";
        String s = String.valueOf(o);
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
