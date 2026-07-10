package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.ai.ClaudeService;
import com.gridstore.huevista.image.model.ImageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Optional "hybrid" step before cleaning: a cheap Claude vision call that looks at THIS
 * specific photo and returns a short, image-grounded addendum for the cleaning prompt —
 * the exact clutter to remove and the exact elements to preserve. Appended to the
 * scene base prompt so the image-editing model gets precise instructions instead of a
 * generic list.
 *
 * Fails soft: returns empty on any error or when not configured, so the cleaner simply
 * falls back to its base interior/exterior prompt. Uses the cheap Haiku model.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleaningHintService {

    private final ClaudeService claude;

    @Value("${app.claude.model:claude-haiku-4-5-20251001}")
    private String model;

    @Value("${replicate.image-cleaner.hybrid-hints-enabled:true}")
    private boolean enabled;

    /**
     * @return a short "REMOVE: … / PRESERVE: …" addendum grounded in this image, or empty.
     */
    public Optional<String> describeCleanup(String imageUrl, ImageType scene) {
        if (!enabled || !claude.isEnabled()) {
            return Optional.empty();
        }
        boolean exterior = scene != ImageType.INDOOR;
        String sceneWord = exterior ? "building exterior" : "interior room";
        // Exteriors can be photographed mid-construction (half-plastered walls), so we also
        // ask for a FINISH list there; interiors only get REMOVE/PRESERVE.
        String finishList = exterior
                ? "FINISH: any wall that is clearly unfinished or only partly plastered — bare "
                + "cement, raw brick/blockwork, or patchy half-applied plaster — that should be "
                + "completed into one smooth paintable plastered wall. Omit this list if every "
                + "wall is already finished.\n"
                : "";
        String headings = exterior ? "'REMOVE:', 'PRESERVE:' and 'FINISH:'" : "'REMOVE:' and 'PRESERVE:'";
        String instruction =
                "You are preparing edit instructions to CLEAN this " + sceneWord + " photo for a paint "
              + "visualizer (remove clutter, keep the structure identical). Look at THIS image and output "
              + "short bulleted lists, nothing else:\n"
              + "REMOVE: the specific clutter, temporary objects, and damage actually visible here. "
              + (exterior
                  ? "Be sure to call out — if present anywhere in the frame, including against the open "
                  + "sky or off to the side — overhead wires and cables, electricity/utility/telephone "
                  + "poles, street-light poles / lamp posts, and trees or overhanging tree branches "
                  + "(even bare twigs silhouetted against the sky). "
                  : "")
              + "\n"
              + "PRESERVE: the specific architectural features actually visible here that must stay "
              + "identical (windows, doors, frames, fixtures, cabinetry, railings, etc.).\n"
              + finishList
              + "Be concrete and brief, one item per line. No preamble and no headings other than "
              + headings + ".";
        try {
            String hints = claude.askUser(model, 400, List.of(
                    ClaudeService.imageUrlBlock(imageUrl),
                    ClaudeService.textBlock(instruction)
            ));
            log.info("CleaningHintService produced image-specific hints ({} chars)", hints.length());
            return Optional.of(hints);
        } catch (Exception e) {
            log.warn("CleaningHintService failed, using base prompt only: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
