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
        // Both scenes can be photographed mid-construction — an unplastered brick shell is
        // as common indoors as a half-rendered facade — so both get a FINISH list. Indoors
        // the ceiling and floor can be raw too, not just the walls.
        //
        // "Unfinished" is NOT only rough masonry. A wall that is fully and evenly plastered
        // but was never puttied or painted is the state most of these photos are actually
        // taken in, and it is the one most easily read as finished: it is flat, so nothing
        // about it looks like a building site. Naming it explicitly is what stops this list
        // coming back empty for a room whose every wall is still bare grey plaster — which
        // then told the cleaner there was nothing to finish.
        String finishList =
                "FINISH: any " + (exterior ? "wall" : "wall, ceiling or floor")
              + " that is clearly unfinished or only partly plastered — bare cement, raw "
              + "brick/blockwork, patchy half-applied plaster"
              + (exterior ? ""
                  : ", a bare concrete ceiling soffit (call out shuttering/formwork "
                  + "plank lines and board joints if you can see them), a raw cement floor")
              + " — that should be completed into a smooth paintable surface. Include here "
              + "any surface that is smoothly plastered but UNPAINTED — bare grey or sandy "
              + "cement plaster, or a chalky white putty coat with no paint over it, with "
              + "trowel sweeps, float marks, damp patches or feathered putty patches "
              + "showing. Smooth does not mean finished: a wall is finished only once it is "
              + "plastered, puttied AND painted, so say when the putty coat or the paint is "
              + "still missing. Omit this "
              + "list if every surface is already finished. Do not list an intentional "
              + "exposed-brick, stone or tile feature in an otherwise finished "
              + (exterior ? "facade" : "room") + " — that is a design choice, not "
              + "unfinished work.\n";
        String headings = "'REMOVE:', 'PRESERVE:' and 'FINISH:'";
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
              + "Only list things that are ACTUALLY VISIBLE in this photo. Never suggest adding, "
              + "staging, decorating, replacing or upgrading anything, and never describe how the "
              + "space 'could' or 'should' look — the edit must not invent objects that are not "
              + "already there.\n"
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
