package com.gridstore.huevista.image.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.CORSRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two decisions that keep the bucket-CORS bootstrap safe to run on every boot:
 * do not write when the bucket already works, and do not destroy rules we did not
 * write. Both matter because {@code putBucketCors} replaces the entire configuration.
 */
class S3CorsPolicyTest {

    private static CORSRule rule(String id, List<String> origins, List<String> methods) {
        return CORSRule.builder().id(id).allowedOrigins(origins).allowedMethods(methods).build();
    }

    @Test
    @DisplayName("origins are split, trimmed, de-duplicated and stripped of trailing slashes")
    void parsesOrigins() {
        // The trailing slash is the one that bites: the browser sends an origin
        // without one, so `https://app.huevista.org/` matches nothing at all.
        List<String> origins = S3CorsPolicy.origins(
                " https://app.huevista.org/ , http://localhost:3000 ,, https://app.huevista.org ");

        assertThat(origins).containsExactly("https://app.huevista.org", "http://localhost:3000");
    }

    @Test
    @DisplayName("no configured origins yields no rule to write")
    void emptyConfiguration() {
        assertThat(S3CorsPolicy.origins(null)).isEmpty();
        assertThat(S3CorsPolicy.origins("  ")).isEmpty();
    }

    @Test
    @DisplayName("an existing rule that already allows the origins counts as covered")
    void coveredByExistingRule() {
        List<CORSRule> existing = List.of(
                rule("hand-made", List.of("https://app.huevista.org"), List.of("GET")));

        assertThat(S3CorsPolicy.covers(existing, List.of("https://app.huevista.org"))).isTrue();
    }

    @Test
    @DisplayName("a rule for a different origin, or for writes only, does not count")
    void notCovered() {
        List<CORSRule> otherOrigin = List.of(
                rule("x", List.of("https://example.com"), List.of("GET")));
        List<CORSRule> writeOnly = List.of(
                rule("x", List.of("https://app.huevista.org"), List.of("PUT")));

        assertThat(S3CorsPolicy.covers(otherOrigin, List.of("https://app.huevista.org"))).isFalse();
        assertThat(S3CorsPolicy.covers(writeOnly, List.of("https://app.huevista.org"))).isFalse();
    }

    @Test
    @DisplayName("every configured origin must be covered, not just one of them")
    void partialCoverageIsNotCoverage() {
        List<CORSRule> existing = List.of(
                rule("x", List.of("https://app.huevista.org"), List.of("GET")));

        assertThat(S3CorsPolicy.covers(existing,
                List.of("https://app.huevista.org", "https://shop.huevista.org"))).isFalse();
    }

    @Test
    @DisplayName("S3 wildcards are honoured the way S3 honours them")
    void wildcardMatching() {
        assertThat(S3CorsPolicy.originMatches("*", "https://anything.example")).isTrue();
        assertThat(S3CorsPolicy.originMatches("https://*.huevista.org", "https://app.huevista.org")).isTrue();
        assertThat(S3CorsPolicy.originMatches("https://*.huevista.org", "https://huevista.org")).isFalse();
        assertThat(S3CorsPolicy.originMatches("https://app.huevista.org", "https://app.huevista.org")).isTrue();
        assertThat(S3CorsPolicy.originMatches("https://app.huevista.org", "http://app.huevista.org")).isFalse();
    }

    @Test
    @DisplayName("merging keeps other people's rules and replaces only our own")
    void mergePreservesForeignRules() {
        CORSRule foreign = rule("analytics-upload", List.of("https://example.com"), List.of("PUT"));
        CORSRule ours = rule(S3CorsPolicy.RULE_ID, List.of("https://old.huevista.org"), List.of("GET"));

        List<CORSRule> merged = S3CorsPolicy.merge(List.of(foreign, ours), List.of("https://app.huevista.org"));

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0)).isEqualTo(foreign);
        assertThat(merged.get(1).id()).isEqualTo(S3CorsPolicy.RULE_ID);
        // Replaced, not appended — a rebooted app must not accumulate stale origins.
        assertThat(merged.get(1).allowedOrigins()).containsExactly("https://app.huevista.org");
    }

    @Test
    @DisplayName("the rule grants reads and nothing else")
    void ruleIsReadOnly() {
        CORSRule written = S3CorsPolicy.rule(List.of("https://app.huevista.org"));

        assertThat(written.allowedMethods()).containsExactlyInAnyOrder("GET", "HEAD");
        assertThat(written.allowedMethods()).doesNotContain("PUT", "POST", "DELETE");
        assertThat(written.exposeHeaders()).contains("ETag");
    }

    @Test
    @DisplayName("the fallback log carries a payload the aws CLI accepts")
    void jsonPayload() {
        String json = S3CorsPolicy.asJson(List.of("https://app.huevista.org", "http://localhost:3000"));

        assertThat(json).isEqualTo("{\"CORSRules\":[{\"ID\":\"" + S3CorsPolicy.RULE_ID + "\","
                + "\"AllowedOrigins\":[\"https://app.huevista.org\",\"http://localhost:3000\"],"
                + "\"AllowedMethods\":[\"GET\",\"HEAD\"],\"AllowedHeaders\":[\"*\"],"
                + "\"ExposeHeaders\":[\"ETag\",\"Content-Length\"],\"MaxAgeSeconds\":3600}]}");
    }
}
