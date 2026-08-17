package com.gridstore.huevista.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The image models an ADMIN may pick from when testing a run, and the only thing that
 * says which model ids are acceptable to send to Replicate.
 *
 * <h2>Why an allow-list rather than a free-text field</h2>
 *
 * The pipeline's models are configuration ({@code replicate.image-cleaner.model},
 * {@code replicate.nano-banana.model}) precisely so a newer tier can be swapped in
 * without a deploy. The admin override is the same idea moved to a single run — "clean
 * this photo with FLUX 2 Max instead and let me look at the difference" — but it arrives
 * over HTTP, and a model id is pasted straight into a Replicate URL. A free-text field
 * would let anything holding an admin session name any endpoint on that host, so the
 * value is matched against this list and a request naming anything else is refused
 * before it reaches the queue.
 *
 * <p>The list is also what the studio's radio buttons are built from — the admin picks
 * from what the backend will actually accept, rather than from a copy of the list that
 * drifts the first time this one changes.
 *
 * <h2>Two stages, one list</h2>
 *
 * A model that can edit a photo can do either job here: the clean is "retouch this
 * photo", the mask is "flood each surface with a flat colour", and both are one
 * image-edit prediction over one input image. So one catalogue serves both, and
 * {@link ReplicateImageEditor#familyOf} works out the request schema per model.
 *
 * <p>Configuration: {@code replicate.selectable-models} — comma-separated, each entry
 * either {@code owner/model} or {@code owner/model|Human label}. Setting it REPLACES
 * the built-in list, so a model that appears on Replicate tomorrow is testable today.
 */
@Slf4j
@Component
public class AiModelCatalogue {

    /**
     * One pickable model.
     *
     * @param id     the Replicate model id, {@code owner/name}
     * @param label  what the studio shows beside the radio button
     * @param family the request schema it speaks, so the UI can say when two options
     *               differ by more than a version number
     */
    public record Option(String id, String label, String family) {}

    /**
     * The default list. Ordered the way an admin comparing them would work through it:
     * the two models the pipeline actually runs first, then the rest of the Gemini
     * family, then a different family each time.
     *
     * <p>Nothing here is checked against Replicate at startup — an id that has been
     * retired simply fails its prediction and says so in the log, which is the same
     * thing that happens to a retired id in {@code replicate.image-cleaner.model}.
     */
    private static final List<Option> BUILT_IN = List.of(
            option("google/nano-banana-pro", "Nano Banana Pro (Gemini 3 Pro Image)"),
            option("google/nano-banana-2", "Nano Banana 2"),
            option("google/nano-banana", "Nano Banana (Gemini 2.5 Flash Image)"),
            option("black-forest-labs/flux-2-max", "FLUX 2 Max"),
            option("black-forest-labs/flux-2-pro", "FLUX 2 Pro"),
            option("black-forest-labs/flux-kontext-max", "FLUX.1 Kontext Max"),
            option("openai/gpt-image-1", "GPT Image 1 (needs OPENAI_API_KEY)"),
            option("bytedance/seedream-4", "Seedream 4"));

    @Value("${replicate.selectable-models:}")
    private String configured;

    /** id → option, in the order they should be offered. */
    private volatile Map<String, Option> byId;

    /** Everything an admin may choose, in display order. */
    public List<Option> options() {
        return List.copyOf(resolve().values());
    }

    /** Whether {@code modelId} is one this deployment will run. */
    public boolean isAllowed(String modelId) {
        return modelId != null && resolve().containsKey(modelId.trim());
    }

    /**
     * The model id to actually use for a stage, given what the admin asked for.
     *
     * <p>Blank (the normal case) means "whatever the configuration says", and the caller
     * keeps its own {@code @Value} model. An id that is not in the catalogue is rejected
     * rather than quietly ignored: a typo'd override that silently ran the default model
     * would show the admin a result they would then attribute to the wrong model, which
     * is worse than no answer at all.
     *
     * @throws IllegalArgumentException when a non-blank id is not in the catalogue
     */
    public Optional<String> resolveOverride(String requested) {
        if (requested == null || requested.isBlank()) return Optional.empty();
        String id = requested.trim();
        if (!isAllowed(id)) {
            throw new IllegalArgumentException(
                    "Unknown image model \"" + id + "\". Pick one of: " + String.join(", ", resolve().keySet()));
        }
        return Optional.of(id);
    }

    /** The label for an id, falling back to the id itself for one no longer listed. */
    public String labelFor(String modelId) {
        Option option = modelId == null ? null : resolve().get(modelId.trim());
        return option == null ? String.valueOf(modelId) : option.label();
    }

    private Map<String, Option> resolve() {
        Map<String, Option> resolved = byId;
        if (resolved == null) {
            synchronized (this) {
                resolved = byId;
                if (resolved == null) {
                    resolved = parse(configured);
                    byId = resolved;
                }
            }
        }
        return resolved;
    }

    /**
     * Reads {@code replicate.selectable-models}, falling back to {@link #BUILT_IN}.
     * A configured list REPLACES the defaults rather than adding to them — the point of
     * setting it is usually to narrow the choice to the models an account can actually
     * bill, and appending would defeat that.
     */
    private static Map<String, Option> parse(String configured) {
        List<Option> source = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            for (String entry : configured.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) continue;
                int bar = trimmed.indexOf('|');
                String id = (bar < 0 ? trimmed : trimmed.substring(0, bar)).trim();
                String label = bar < 0 ? null : trimmed.substring(bar + 1).trim();
                if (id.isEmpty()) continue;
                source.add(label == null || label.isEmpty() ? option(id, labelFrom(id))
                        : option(id, label));
            }
            if (source.isEmpty()) {
                log.warn("replicate.selectable-models was set but named no usable model — "
                        + "falling back to the built-in list");
            }
        }
        if (source.isEmpty()) source = BUILT_IN;

        Map<String, Option> ordered = new LinkedHashMap<>();
        for (Option option : source) ordered.putIfAbsent(option.id(), option);
        return ordered;
    }

    private static Option option(String id, String label) {
        return new Option(id, label, ReplicateImageEditor.familyOf(id).name());
    }

    /** A readable label for a configured id nobody gave one to: "flux-2-max" → "Flux 2 Max". */
    private static String labelFrom(String id) {
        String name = id.substring(id.indexOf('/') + 1).replace('-', ' ').replace('_', ' ');
        return Arrays.stream(name.split(" "))
                .filter(word -> !word.isEmpty())
                .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1))
                .reduce((a, b) -> a + " " + b)
                .orElse(id);
    }
}
