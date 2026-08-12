package com.gridstore.huevista.project.service;

import com.gridstore.huevista.project.service.AiFailureSimulator.Stage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The knob that makes the image models decline on demand.
 *
 * Two things matter here and neither is the happy path: that a per-run choice can
 * override the deployment-wide one IN BOTH DIRECTIONS (otherwise a shared staging box
 * either simulates for everybody or for nobody), and that a value outside the
 * vocabulary is refused rather than ignored — a silently-dropped {@code "MASKS"} runs
 * an honest pipeline and looks exactly like the failure path being broken.
 */
class AiFailureSimulatorTest {

    private final AiFailureSimulator simulator = new AiFailureSimulator();

    private void globally(String setting) {
        ReflectionTestUtils.setField(simulator, "globalSimulation", setting);
    }

    @Test
    void nothing_is_simulated_by_default() {
        globally("NONE");

        assertThat(simulator.simulates(Stage.CLEAN, null)).isFalse();
        assertThat(simulator.simulates(Stage.MASK, null)).isFalse();
    }

    @Test
    void the_global_setting_applies_when_a_run_asks_for_nothing() {
        globally("MASK");

        assertThat(simulator.simulates(Stage.MASK, null)).isTrue();
        // Only the named half: the clean must still run for real, or the rehearsal is
        // of "nothing worked" rather than of "cleaned, but no walls".
        assertThat(simulator.simulates(Stage.CLEAN, null)).isFalse();
    }

    @Test
    void both_covers_the_two_halves() {
        globally("BOTH");

        assertThat(simulator.simulates(Stage.CLEAN, null)).isTrue();
        assertThat(simulator.simulates(Stage.MASK, null)).isTrue();
    }

    @Test
    void a_runs_own_choice_wins_over_the_deployment_setting() {
        globally("NONE");

        assertThat(simulator.simulates(Stage.CLEAN, "clean")).isTrue();
        assertThat(simulator.simulates(Stage.MASK, "clean")).isFalse();
    }

    @Test
    void a_run_can_force_an_honest_pipeline_on_a_simulating_deployment() {
        // The direction that is easy to forget, and the one that makes this usable on a
        // shared box: one admin rehearsing a failure must not stop the next person
        // running a real project through the same deployment.
        globally("BOTH");

        assertThat(simulator.simulates(Stage.CLEAN, "NONE")).isFalse();
        assertThat(simulator.simulates(Stage.MASK, "NONE")).isFalse();
    }

    @Test
    void blank_means_nothing_was_asked_for_rather_than_none() {
        globally("MASK");

        assertThat(simulator.simulates(Stage.MASK, "   ")).isTrue();
    }

    @Test
    void parse_canonicalises_and_refuses_anything_it_does_not_know() {
        assertThat(AiFailureSimulator.parse(" mask ")).isEqualTo("MASK");
        assertThat(AiFailureSimulator.parse("")).isNull();
        assertThat(AiFailureSimulator.parse(null)).isNull();

        assertThatThrownBy(() -> AiFailureSimulator.parse("MASKS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONE, CLEAN, MASK or BOTH");
    }
}
