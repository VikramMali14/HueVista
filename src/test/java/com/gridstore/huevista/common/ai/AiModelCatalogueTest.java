package com.gridstore.huevista.common.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The allow-list behind the admin's model radio buttons.
 *
 * <p>Two things matter here and neither is cosmetic. A model id is pasted into a
 * Replicate URL, so anything not on this list must be refused rather than forwarded.
 * And a typo must be refused LOUDLY: silently running the default model would show the
 * admin a result they would then attribute to the model they thought they picked, which
 * is the one failure mode a comparison tool cannot have.
 */
class AiModelCatalogueTest {

    private AiModelCatalogue catalogue(String configured) {
        AiModelCatalogue catalogue = new AiModelCatalogue();
        ReflectionTestUtils.setField(catalogue, "configured", configured);
        return catalogue;
    }

    @Test
    void theBuiltInListCoversTheModelsWorthComparing() {
        AiModelCatalogue catalogue = catalogue("");

        assertThat(catalogue.options()).extracting(AiModelCatalogue.Option::id)
                .containsExactly(
                        "google/nano-banana-pro",
                        "google/nano-banana-2",
                        "google/nano-banana",
                        "black-forest-labs/flux-2-max",
                        "black-forest-labs/flux-2-pro",
                        "black-forest-labs/flux-kontext-max",
                        "openai/gpt-image-1",
                        "bytedance/seedream-4");
    }

    @Test
    void everyOptionCarriesTheRequestSchemaItSpeaks() {
        // The studio shows this so two options that differ by more than a version number
        // say so — and it is the same answer ReplicateImageEditor builds the body from.
        assertThat(catalogue("").options())
                .filteredOn(o -> o.id().equals("black-forest-labs/flux-kontext-max"))
                .singleElement()
                .extracting(AiModelCatalogue.Option::family)
                .isEqualTo(ReplicateImageEditor.Family.FLUX_KONTEXT.name());
    }

    @Test
    void aModelOutsideTheListIsRefusedRatherThanForwarded() {
        AiModelCatalogue catalogue = catalogue("");

        assertThat(catalogue.isAllowed("google/nano-banana-pro")).isTrue();
        assertThat(catalogue.isAllowed("../../account/api-tokens")).isFalse();
        assertThatThrownBy(() -> catalogue.resolveOverride("some-owner/whatever"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown image model");
    }

    @Test
    void nothingAskedForIsNotAnError() {
        // The normal case by a wide margin: no override, use the configured model.
        assertThat(catalogue("").resolveOverride(null)).isEmpty();
        assertThat(catalogue("").resolveOverride("   ")).isEmpty();
        assertThat(catalogue("").resolveOverride(" google/nano-banana-2 "))
                .isEqualTo(Optional.of("google/nano-banana-2"));
    }

    @Test
    void configurationReplacesTheListSoADeploymentCanNarrowOrExtendIt() {
        AiModelCatalogue catalogue = catalogue(
                "google/nano-banana-pro|Pro, black-forest-labs/flux-3-ultra ,, google/nano-banana-pro");

        assertThat(catalogue.options()).extracting(AiModelCatalogue.Option::id)
                .containsExactly("google/nano-banana-pro", "black-forest-labs/flux-3-ultra");
        assertThat(catalogue.labelFor("google/nano-banana-pro")).isEqualTo("Pro");
        // A model nobody labelled still reads as something in a radio list.
        assertThat(catalogue.labelFor("black-forest-labs/flux-3-ultra")).isEqualTo("Flux 3 Ultra");
        // And the built-ins are gone — narrowing the list is usually the point.
        assertThat(catalogue.isAllowed("openai/gpt-image-1")).isFalse();
    }

    @Test
    void aConfiguredListWithNothingUsableFallsBackRatherThanOfferingNothing() {
        // An empty radio group would look like the feature is broken; the built-in list
        // is a better answer to a malformed setting than no list at all.
        assertThat(catalogue(" , ,").options()).isNotEmpty();
    }
}
