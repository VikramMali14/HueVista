package com.gridstore.huevista.image.config;

import software.amazon.awssdk.services.s3.model.CORSRule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The CORS rule the storage bucket needs, and how to reconcile it with whatever is
 * already on the bucket.
 *
 * A presigned GET is fetchable by anyone holding the URL, but that is a different
 * question from whether a BROWSER may read the response: for that, S3 has to answer
 * with {@code Access-Control-Allow-Origin}, and it only does so when the BUCKET
 * carries a CORS configuration naming the calling site. Nothing in a presigned URL
 * changes that.
 *
 * It matters because the frontend loads every image it recolours with
 * {@code crossOrigin="anonymous"} — a canvas cannot be read back otherwise — which
 * makes each load a CORS request. With no rule on the bucket, those loads are
 * blocked outright and the room renders as an empty frame. The share page was where
 * it showed: a link forwarded over WhatsApp opened on a blank photo.
 *
 * Kept apart from {@link S3BucketCorsInitializer} so the matching and merging rules
 * below can be tested without an S3 client.
 */
public final class S3CorsPolicy {

    private S3CorsPolicy() {}

    /**
     * Marks the rule this application manages.
     *
     * Without an ID there is no way to tell our rule from an operator's, and each
     * restart would either append a duplicate or overwrite work someone did by hand.
     * With one, a reconcile replaces exactly this rule and leaves every other alone.
     */
    public static final String RULE_ID = "huevista-browser-read";

    /** Reading images is all the browser does here — nothing else is granted. */
    private static final List<String> METHODS = List.of("GET", "HEAD");

    /** Preflight lifetime, in seconds. Matches the API's own {@code maxAge}. */
    private static final int MAX_AGE_SECONDS = 3600;

    /**
     * The origins from {@code app.cors.allowed-origins}, in order and without repeats.
     *
     * Deliberately the same property the API's own CORS uses: the sites allowed to
     * call the API are exactly the sites that display its images, and a bucket that
     * trusted a different list from the API would be a second place to remember when
     * a domain changes.
     */
    public static List<String> origins(String configured) {
        if (configured == null || configured.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : configured.split(",")) {
            String origin = raw.trim();
            // A trailing slash makes an origin S3 will never match — the browser
            // sends `https://app.huevista.org`, never `https://app.huevista.org/`.
            while (origin.endsWith("/")) origin = origin.substring(0, origin.length() - 1);
            if (!origin.isEmpty()) out.add(origin);
        }
        return List.copyOf(out);
    }

    /**
     * Whether {@code rules} already lets every one of {@code origins} read an object.
     *
     * Checked before writing anything, so a bucket configured by hand — or by an
     * earlier boot — is left exactly as it is. Being conservative in the other
     * direction would be worse: rewriting a working configuration on every restart
     * is how a hand-made exception quietly disappears.
     */
    public static boolean covers(List<CORSRule> rules, List<String> origins) {
        if (origins.isEmpty()) return true;
        return origins.stream().allMatch(origin -> rules.stream().anyMatch(r -> allows(r, origin)));
    }

    private static boolean allows(CORSRule rule, String origin) {
        boolean readable = rule.allowedMethods().stream()
                .anyMatch(m -> "GET".equalsIgnoreCase(m) || "*".equals(m));
        if (!readable) return false;
        return rule.allowedOrigins().stream().anyMatch(pattern -> originMatches(pattern, origin));
    }

    /**
     * S3's own matching: a bare {@code *} allows anything, and a pattern may contain
     * at most one {@code *} standing for any run of characters.
     */
    static boolean originMatches(String pattern, String origin) {
        if (pattern == null) return false;
        String p = pattern.trim();
        if (p.equals("*")) return true;
        String o = origin.toLowerCase(Locale.ROOT);
        String lower = p.toLowerCase(Locale.ROOT);
        int star = lower.indexOf('*');
        if (star < 0) return lower.equals(o);
        String head = lower.substring(0, star);
        String tail = lower.substring(star + 1);
        return o.length() >= head.length() + tail.length() && o.startsWith(head) && o.endsWith(tail);
    }

    /** The rule this application writes: read-only, for the configured sites. */
    public static CORSRule rule(List<String> origins) {
        return CORSRule.builder()
                .id(RULE_ID)
                .allowedOrigins(origins)
                .allowedMethods(METHODS)
                // The browser sends none of its own on an image load, but a Range
                // request (video, resumable fetch) needs this to stay permissive.
                .allowedHeaders("*")
                // Without ETag exposed, a conditional re-fetch cannot be built by
                // client code — cheap to allow, awkward to add later.
                .exposeHeaders("ETag", "Content-Length")
                .maxAgeSeconds(MAX_AGE_SECONDS)
                .build();
    }

    /**
     * The full rule list to write: everything already on the bucket that we did not
     * put there, plus our own rule.
     *
     * {@code putBucketCors} REPLACES the whole configuration — there is no way to add
     * one rule — so anything dropped from this list is deleted from the bucket. Hence
     * the careful preservation of rules that aren't ours.
     */
    public static List<CORSRule> merge(List<CORSRule> existing, List<String> origins) {
        List<CORSRule> out = new ArrayList<>();
        for (CORSRule r : existing) {
            if (!RULE_ID.equals(r.id())) out.add(r);
        }
        out.add(rule(origins));
        return out;
    }

    /**
     * The rule as the {@code aws s3api put-bucket-cors} payload, for the log line an
     * operator reads when this application's own IAM role may not write it.
     */
    public static String asJson(List<String> origins) {
        StringBuilder sb = new StringBuilder("{\"CORSRules\":[{\"ID\":\"").append(RULE_ID)
                .append("\",\"AllowedOrigins\":[");
        for (int i = 0; i < origins.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(origins.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        sb.append("],\"AllowedMethods\":[");
        for (int i = 0; i < METHODS.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(METHODS.get(i)).append('"');
        }
        sb.append("],\"AllowedHeaders\":[\"*\"],\"ExposeHeaders\":[\"ETag\",\"Content-Length\"],")
                .append("\"MaxAgeSeconds\":").append(MAX_AGE_SECONDS).append("}]}");
        return sb.toString();
    }
}
