package com.gridstore.huevista.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The WEBSITE's origin — where a link handed to a human has to point.
 *
 * Anything a person clicks (a share link, an unsubscribe link) must open the site, not
 * this API: the recipient typically has no app, and the JSON endpoint behind the page is
 * useless to them. Explicit config wins; otherwise the first CORS allowed origin is
 * taken, which is the website in every deployment that has one; only with neither set
 * does this fall back to the API's own origin — a single-host dev box, where the two are
 * the same thing anyway.
 *
 * <p>One component rather than a copy of the fallback per caller, because the failure
 * mode of a second copy is silent: it keeps working on the dev box where every origin is
 * the same and emits links to the wrong host in production.
 */
@Component
public class SiteUrls {

    @Value("${app.web-base-url:}")
    private String webBaseUrl;

    @Value("${app.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @Value("${app.base-url:http://localhost:8080}")
    private String apiBaseUrl;

    /** The website origin, without a trailing slash. */
    public String website() {
        String configured = firstOrigin(webBaseUrl);
        if (configured != null) return configured;
        String cors = firstOrigin(corsAllowedOrigins);
        return cors != null ? cors : trimTrailingSlash(apiBaseUrl);
    }

    /** {@code website()} + {@code path} (which must start with "/"). */
    public String on(String path) {
        return website() + path;
    }

    /** First entry of a comma-separated origin list, trimmed and slash-stripped, or null. */
    private static String firstOrigin(String csv) {
        if (csv == null || csv.isBlank()) return null;
        for (String part : csv.split(",")) {
            String origin = trimTrailingSlash(part.trim());
            // "*" is a CORS wildcard, not an address anything can be linked to.
            if (!origin.isEmpty() && !"*".equals(origin)) return origin;
        }
        return null;
    }

    private static String trimTrailingSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
