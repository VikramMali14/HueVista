package com.gridstore.huevista.auth.handler;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Which client started a Google sign-in, carried across the Google round-trip.
 *
 * The OAuth flow leaves our origin entirely and comes back on a fresh top-level
 * GET, so the handler that finally issues the exchange code has nothing left of
 * the original request — including the one thing it now needs to know: whether
 * to land the browser on the website or hand the code back to the phone app.
 *
 * A short-lived, HttpOnly, SameSite=Lax cookie set at the authorization request
 * survives exactly that trip (Google's callback is a top-level navigation, so a
 * Lax cookie rides along) and is the same trick the website already uses for its
 * own `hv_oauth_next`. It carries no secret: the worst a forged value can do is
 * send the caller's own exchange code to the app scheme instead of the site.
 */
public final class OAuthClientHint {

    /** Cookie name — namespaced with the app's other `hv_` cookies. */
    public static final String COOKIE = "hv_oauth_client";

    /** The value the mobile app sends as `?client=` on the authorization request. */
    public static final String MOBILE = "mobile";

    /** Ten minutes is longer than any human takes to pick a Google account. */
    private static final int MAX_AGE_SECONDS = 600;

    private OAuthClientHint() {}

    /** Remember the client for the length of one OAuth round-trip. */
    public static void remember(HttpServletResponse response, String client, boolean secure) {
        Cookie cookie = new Cookie(COOKIE, client);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(MAX_AGE_SECONDS);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /** True when this callback belongs to a sign-in the mobile app started. */
    public static boolean isMobile(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName())) return MOBILE.equals(c.getValue());
        }
        return false;
    }

    /** Drop the hint once the round-trip is over, however it ended. */
    public static void clear(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() == null) return;
        Cookie cookie = new Cookie(COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
