package com.gridstore.huevista.project.service;

/**
 * Where a long AI step says what it is doing, in words meant for the person waiting.
 *
 * <h2>Why this exists at all</h2>
 *
 * Both halves of the pipeline work through a chain of models, and a model that is merely
 * busy hands over to the next one. That is the right behaviour, and it used to be
 * completely invisible: the studio showed one unchanging "Detecting walls…" spinner
 * whether the first model answered in forty seconds or the fourth answered in six
 * minutes. A run that was working looked exactly like a run that had hung, and the
 * rational thing to do about a hung page is close it — which is the one action that
 * actually loses the work.
 *
 * <p>So the services take one of these and call it as they move. The note is a full
 * sentence, already fit to render: "Still cleaning up your photo — this is taking a
 * moment (2 of 4)". Deciding what to DO with it is the caller's business —
 * {@link SegmentationService} writes it onto the project row, tests collect it into a
 * list, and everything else passes {@link #NONE}.
 *
 * <h2>What it is not</h2>
 *
 * Not a log, and not an error channel. Failures still go through exceptions and
 * {@code log.warn} exactly as before; this carries only the running commentary a user is
 * allowed to see, which is why it never carries an exception message.
 *
 * <p>It also NAMES NO MODEL. Which supplier answered is an operator's fact, not a
 * product feature: it changes without notice, it means nothing to the person waiting,
 * and naming one that has just declined reads as an admission that something is broken.
 * The models are logged, by their catalogue labels, where that detail is wanted. What a
 * note owes the user is only that the run is alive and roughly how far along it is.
 *
 * <p>Implementations must be cheap and must not throw — a note lands in the middle of a
 * model chain, and losing a run because the progress write failed would be an absurd
 * trade. The services call {@link #say} defensively for that reason.
 */
@FunctionalInterface
public interface ProgressListener {

    /** Nobody is watching — the overwhelmingly common case outside a real run. */
    ProgressListener NONE = note -> { };

    /**
     * Report where the run has got to.
     *
     * @param note one sentence, already written for a user rather than an operator
     */
    void say(String note);
}
