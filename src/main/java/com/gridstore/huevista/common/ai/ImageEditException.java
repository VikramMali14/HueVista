package com.gridstore.huevista.common.ai;

import java.util.Locale;

/**
 * An image-editing provider did not hand back an image.
 *
 * <p>The interesting part is the {@link Disposition}, not the message. Every caller of
 * an image model has to answer the same question when one says no — ask again, ask
 * someone else, or stop? — and the providers answer it badly. Replicate reports a busy
 * model ({@code ModelRateLimitError: Service is currently unavailable due to high
 * demand (E003)}), a model that refused the photo, and a model that simply never
 * finished, all through the same free-text {@code error} field on a prediction. Google
 * buries the same distinction in an RPC status name. Sorting it out once, here, is
 * what lets a caller retry a queue and not retry a refusal.
 */
public class ImageEditException extends RuntimeException {

    public enum Disposition {
        /** Capacity or transport. The same provider is worth asking again shortly. */
        RETRY,
        /** Nothing to gain by waiting on this provider again — another one may still deliver. */
        FAILOVER,
        /** The refusal is about the image or the request. Anyone else will refuse it too. */
        GIVE_UP
    }

    private final Disposition disposition;

    public ImageEditException(String message, Disposition disposition) {
        super(message);
        this.disposition = disposition;
    }

    public Disposition disposition() {
        return disposition;
    }

    /** Worth another go at the SAME provider. */
    public boolean retryable() {
        return disposition == Disposition.RETRY;
    }

    /** Worth offering to a DIFFERENT provider. */
    public boolean worthFailingOver() {
        return disposition != Disposition.GIVE_UP;
    }

    public static ImageEditException retry(String message) {
        return new ImageEditException(message, Disposition.RETRY);
    }

    public static ImageEditException failover(String message) {
        return new ImageEditException(message, Disposition.FAILOVER);
    }

    public static ImageEditException giveUp(String message) {
        return new ImageEditException(message, Disposition.GIVE_UP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Reading a provider's mind from the only thing it gives us: prose
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Classify a provider's free-text error.
     *
     * <p>Matched on text because at this layer text is all there is: a Replicate
     * prediction that ends {@code failed} carries one {@code error} string and no code,
     * and what is in it originates from whichever upstream model ran. An unrecognised
     * error is {@link Disposition#FAILOVER} rather than {@link Disposition#GIVE_UP}:
     * being wrong the first way costs one call to the other provider, being wrong the
     * second way costs the user their clean.
     */
    public static Disposition classify(String providerError) {
        if (providerError == null || providerError.isBlank()) return Disposition.FAILOVER;
        String s = providerError.toLowerCase(Locale.ROOT);
        if (looksRefused(s)) return Disposition.GIVE_UP;
        if (looksRateLimited(s)) return Disposition.RETRY;
        return Disposition.FAILOVER;
    }

    /** "Not now" — the model is busy, throttled, or out of capacity. */
    public static boolean looksRateLimited(String lowercased) {
        return containsAny(lowercased,
                "ratelimit", "rate limit", "rate_limit", "e003",
                "high demand", "resource_exhausted", "resource exhausted",
                "quota", "too many requests", "429",
                "capacity", "overloaded", "server is busy",
                "try again later", "currently unavailable", "temporarily unavailable",
                "service unavailable", "internal error", "timeout", "timed out");
    }

    /**
     * "Not this" — the provider looked at the request and declined. Retrying or failing
     * over just buys the same refusal from someone else, so these stop the pipeline and
     * let the original photo be the canvas.
     */
    public static boolean looksRefused(String lowercased) {
        return containsAny(lowercased,
                "nsfw", "safety", "content policy", "prohibited", "blocklist",
                "blocked", "sensitive", "not allowed", "violat", "image_safety",
                "invalid argument", "invalid_argument", "unsupported");
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
