package com.gridstore.huevista.project.service;

import com.gridstore.huevista.project.dto.MaskLabApproach;
import com.gridstore.huevista.project.dto.MaskLabRequest;
import com.gridstore.huevista.project.dto.MaskLabResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The one lab approach that runs entirely on the pixels, and therefore the one
 * whose behaviour can be pinned down without a model on the other end.
 *
 * <p>What it is being held to is not "finds the wall" — that depends on the
 * photograph — but the two properties that make it worth having at all: it
 * returns a mask whose foreground is exactly the surfaces the CLEAN repainted,
 * at the resolution of the image it was given, with no registration step in
 * between. If those hold, the approach has no drift by construction.
 */
class MaskLabPaintedSurfaceTest {

    private MaskLabService service;
    private Map<String, byte[]> stored;

    @BeforeEach
    void setUp() {
        stored = new HashMap<>();
        // A storage stub that keeps the bytes, so a test can decode what the
        // approach actually wrote rather than trusting the URL it returned.
        var storage = new com.gridstore.huevista.image.service.StorageService() {
            @Override
            public String store(org.springframework.web.multipart.MultipartFile file, String userId) {
                return "unused";
            }

            @Override
            public String store(byte[] bytes, String userId, String filename, String contentType) {
                String key = userId + "/" + stored.size() + "-" + filename;
                stored.put(key, bytes);
                return key;
            }

            @Override
            public byte[] load(String storageKey) {
                return stored.get(storageKey);
            }

            @Override
            public void delete(String storageKey) {
                stored.remove(storageKey);
            }

            @Override
            public String getPublicUrl(String storageKey) {
                return "https://example.test/" + storageKey;
            }

            @Override
            public int deleteAll() {
                int n = stored.size();
                stored.clear();
                return n;
            }
        };

        service = new MaskLabService(
                storage,
                mock(ReplicateMaskSegmenter.class),
                mock(com.gridstore.huevista.common.ai.AiModelCatalogue.class),
                mock(org.springframework.web.client.RestTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /**
     * A cleaned facade as the cleaner is instructed to produce one: paintable
     * surfaces repainted white but still SHADED (the prompt says keep the light
     * and shade), a brown door, a charcoal railing, blue sky, grey road.
     */
    private static byte[] cleanedFacade() throws Exception {
        int w = 400, h = 300;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb;
                if (y < 60) {
                    rgb = 0x8FB6D9;                       // sky
                } else if (y > 250) {
                    rgb = 0x8E9AA4;                       // road
                } else if (x > 150 && x < 200 && y > 170) {
                    rgb = 0x5C4033;                       // door leaf
                } else if (x > 300 && x < 320 && y > 120 && y < 200) {
                    rgb = 0x43464A;                       // railing
                } else {
                    // White wall, but lit: bright at the top, in shadow lower
                    // down. Only the ABSENCE of colour is constant.
                    int v = 255 - (y - 60) / 3;
                    rgb = (v << 16) | (v << 8) | v;
                }
                img.setRGB(x, y, rgb);
            }
        }
        // An isolated white speck against the sky: a bright roof tile two
        // streets away, or JPEG noise. Low-chroma and bright, so colour alone
        // calls it a painted surface — the blob filter is what says otherwise.
        for (int y = 20; y < 26; y++) {
            for (int x = 340; x < 346; x++) img.setRGB(x, y, 0xF2F2F2);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private MaskLabResponse run(MaskLabRequest request) throws Exception {
        return service.run(
                new MockMultipartFile("file", "cleaned.png", "image/png", cleanedFacade()),
                request);
    }

    private static MaskLabRequest paintedSurface() {
        MaskLabRequest r = new MaskLabRequest();
        r.setApproach(MaskLabApproach.PAINTED_SURFACE);
        return r;
    }

    private BufferedImage decodeOutput(MaskLabResponse res, String labelStartsWith) throws Exception {
        var output = res.outputs().stream()
                .filter(o -> o.label().startsWith(labelStartsWith))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no output labelled " + labelStartsWith));
        String key = output.url().replace("https://example.test/", "");
        return ImageIO.read(new ByteArrayInputStream(stored.get(key)));
    }

    @Test
    void findsTheShadedWhiteWallAndKeepsTheSkyAndRoadOutOfIt() throws Exception {
        MaskLabResponse res = run(paintedSurface());
        BufferedImage mask = decodeOutput(res, "Paintable");

        // Mid-wall, well inside the lit part and inside the shaded part alike.
        assertThat(isOn(mask, 60, 100)).as("lit wall").isTrue();
        assertThat(isOn(mask, 60, 230)).as("shaded wall").isTrue();
        // The sky is chromatic, so it is not a painted surface.
        assertThat(isOn(mask, 200, 20)).as("sky").isFalse();
    }

    @Test
    void separatesTheDoorAndRailingFromTheWall() throws Exception {
        MaskLabResponse res = run(paintedSurface());

        BufferedImage doors = decodeOutput(res, "Door");
        assertThat(isOn(doors, 175, 220)).as("door leaf").isTrue();
        assertThat(isOn(doors, 60, 100)).as("wall is not a door").isFalse();

        BufferedImage rails = decodeOutput(res, "Railing");
        assertThat(isOn(rails, 310, 160)).as("railing").isTrue();
    }

    @Test
    void writesTheMaskAtTheImagesOwnResolutionSoThereIsNothingToRegister() throws Exception {
        MaskLabResponse res = run(paintedSurface());
        BufferedImage mask = decodeOutput(res, "Paintable");
        // 400×300 is under the working cap, so it comes back untouched. A mask
        // the same size as its canvas is one that needs no scale or offset.
        assertThat(mask.getWidth()).isEqualTo(400);
        assertThat(mask.getHeight()).isEqualTo(300);
    }

    @Test
    void saysWhatItCoveredAndAdmitsWhatItCannotSeparate() throws Exception {
        MaskLabResponse res = run(paintedSurface());
        assertThat(res.approach()).isEqualTo(MaskLabApproach.PAINTED_SURFACE);
        assertThat(res.model()).isNull();
        assertThat(res.note())
                .contains("Paintable")
                .contains("ONE mask");
    }

    @Test
    void keepsTheWallAndDropsTheSpeckBesideIt() throws Exception {
        // Colour alone cannot tell a bright speck from a wall — both are pale
        // and unsaturated. Size can, which is the whole job of the blob filter.
        MaskLabResponse filtered = run(paintedSurface());
        BufferedImage mask = decodeOutput(filtered, "Paintable");
        assertThat(isOn(mask, 60, 100)).as("wall survives").isTrue();
        assertThat(isOn(mask, 342, 22)).as("speck dropped").isFalse();
    }

    @Test
    void keepsTheSpeckWhenTheFilterIsTurnedOff() throws Exception {
        MaskLabRequest permissive = paintedSurface();
        permissive.setMinBlobShare(0.0);
        BufferedImage mask = decodeOutput(run(permissive), "Paintable");
        assertThat(isOn(mask, 342, 22)).as("nothing is filtered at zero").isTrue();
    }

    @Test
    void refusesAFileThatIsNotAnImage() {
        assertThatThrownBy(() -> service.run(
                new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()),
                paintedSurface()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an image");
    }

    @Test
    void refusesAnEmptyUpload() {
        assertThatThrownBy(() -> service.run(
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0]),
                paintedSurface()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Choose a cleaned image");
    }

    @Test
    void tellsYouSamNeedsAPointRatherThanAPrompt() {
        MaskLabRequest r = new MaskLabRequest();
        r.setApproach(MaskLabApproach.SAM_POINTS);
        assertThatThrownBy(() -> service.run(
                new MockMultipartFile("file", "c.png", "image/png", cleanedFacade()), r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompted with positions, not words");
    }

    @Test
    void refusesACustomBodyThatWouldNeverReceiveTheImage() {
        MaskLabRequest r = new MaskLabRequest();
        r.setApproach(MaskLabApproach.CUSTOM_REPLICATE);
        r.setModel("some/segmenter");
        r.setInputTemplate("{\"prompt\": \"wall\"}");
        assertThatThrownBy(() -> service.run(
                new MockMultipartFile("file", "c.png", "image/png", cleanedFacade()), r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{{image}}");
    }

    private static boolean isOn(BufferedImage mask, int x, int y) {
        return (mask.getRGB(x, y) & 0xff) > 127;
    }
}
