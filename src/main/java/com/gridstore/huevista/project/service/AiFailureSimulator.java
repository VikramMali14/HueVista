package com.gridstore.huevista.project.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * TESTING ONLY — makes the image models decline on demand, so the two failure
 * paths of the pipeline can be walked through without waiting for a real outage.
 *
 * <p>Both halves of the automatic run are the same model (Nano Banana Pro on
 * Replicate, with a hierarchy of other providers behind the clean), and both have
 * a recovery path that is very hard to reach deliberately:
 *
 * <ul>
 *   <li><b>{@link Stage#CLEAN}</b> — every cleaning provider declines, so there is
 *       no canvas to align masks to and the run FAILS at the clean stage. See the
 *       gate in {@link SegmentationService#segmentAsync}.</li>
 *   <li><b>{@link Stage#MASK}</b> — the clean lands but wall detection produces
 *       nothing usable. The run does NOT fail: the cleaned photo is handed over,
 *       the walls are left to be marked by hand, and a report goes to the admin
 *       queue on the user's behalf.</li>
 * </ul>
 *
 * <p>Waiting for the models to actually fail is not a test plan, and turning the
 * real API token off fails BOTH stages at once (and fails them as a configuration
 * error rather than as a decline), which is why this exists as its own knob.
 *
 * <h2>Two ways to switch it on</h2>
 * <ol>
 *   <li><b>Globally</b>, via {@code huevista.testing.simulate-ai-failure}
 *       (NONE / CLEAN / MASK / BOTH) — every run in the deployment simulates that
 *       failure. Meant for a local or staging box.</li>
 *   <li><b>Per run</b>, via the ADMIN-only {@code simulateFailure} field on the
 *       segment request, persisted on the project so the async worker — possibly
 *       another JVM reading the Redis queue — sees the same choice. This is the one
 *       to use on a shared environment: one admin can walk a single room through a
 *       failure while everybody else's runs stay real.</li>
 * </ol>
 *
 * <p>The per-run value WINS wherever it is set, in both directions: an admin can
 * force a failure on a box where the global switch is off, and can force a HONEST
 * run (by sending {@code NONE}) on a box where it is on.
 *
 * <p>Nothing here fakes a result — it only withholds one. The models are never
 * called for a simulated stage, so a simulated run also costs nothing.
 */
@Slf4j
@Service
public class AiFailureSimulator {

    /** Which half of the run to make fail. */
    public enum Stage {
        /** The photo clean-up (every provider declines). */
        CLEAN,
        /** Wall detection on the cleaned canvas. */
        MASK
    }

    /** The vocabulary both the property and the per-run field speak. */
    public static final String NONE = "NONE";
    public static final String BOTH = "BOTH";

    @Value("${huevista.testing.simulate-ai-failure:NONE}")
    private String globalSimulation;

    /** Loud startup banner — a deployment must never run with this left on. */
    @PostConstruct
    void warnWhenEnabled() {
        String setting = normalizeOrNull(globalSimulation);
        if (setting == null || NONE.equals(setting)) return;
        log.warn("=================================================================");
        log.warn(" AI FAILURE SIMULATION IS ON: {} (huevista.testing.simulate-ai-failure)", setting);
        log.warn(" The image models will NOT be called for that stage — every run");
        log.warn(" in this deployment takes the failure path. TESTING ONLY.");
        log.warn("=================================================================");
    }

    /**
     * Should this run pretend the given stage failed?
     *
     * @param perRun the project's own {@code simulatedFailure} (null when the admin
     *               set nothing for this run, in which case the global setting decides)
     */
    public boolean simulates(Stage stage, String perRun) {
        String setting = normalizeOrNull(perRun);
        if (setting == null) setting = normalizeOrNull(globalSimulation);
        if (setting == null || NONE.equals(setting)) return false;
        return BOTH.equals(setting) || setting.equals(stage.name());
    }

    /**
     * Validates and canonicalises a value coming in from the API.
     *
     * @return the canonical form, or null for "nothing was asked for" (blank input)
     * @throws IllegalArgumentException on a value outside the vocabulary — a typo'd
     *                                  {@code "MASKS"} silently running an honest
     *                                  pipeline is the worst outcome for a knob whose
     *                                  entire job is to make something fail
     */
    public static String parse(String raw) {
        String value = normalizeOrNull(raw);
        if (value == null) return null;
        if (NONE.equals(value) || BOTH.equals(value)
                || value.equals(Stage.CLEAN.name()) || value.equals(Stage.MASK.name())) {
            return value;
        }
        throw new IllegalArgumentException(
                "simulateFailure must be NONE, CLEAN, MASK or BOTH.");
    }

    private static String normalizeOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
