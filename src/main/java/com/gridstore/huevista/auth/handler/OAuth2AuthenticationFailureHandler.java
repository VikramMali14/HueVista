package com.gridstore.huevista.auth.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Called by Spring Security when OAuth2 authentication fails at any stage
 * (bad state param, token exchange failure, user-denied access, etc.).
 * Redirects the browser back to the sign-in page with an error flag instead of
 * dumping raw JSON in the browser.
 *
 * A sign-in the mobile app started goes back to the app's deep link with the
 * same flag, so the system browser session closes and the app can say "that
 * didn't work" on its own sign-in screen. Left on the website, the failure would
 * strand the user in a browser sheet with nothing to tap.
 */
@Component
@Slf4j
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final String frontendUrl;
    private final String mobileRedirectUri;

    public OAuth2AuthenticationFailureHandler(
            @Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOrigins,
            @Value("${app.mobile.oauth-redirect-uri:huevista://sign-in/callback}") String mobileRedirectUri) {
        this.frontendUrl = firstOrigin(allowedOrigins);
        this.mobileRedirectUri = mobileRedirectUri;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("OAuth2 authentication failed: {}", exception.getMessage());
        boolean mobile = OAuthClientHint.isMobile(request);
        OAuthClientHint.clear(request, response);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", mobile
                ? mobileRedirectUri + "#error=google"
                : frontendUrl + "/sign-in?error=google");
    }

    /** The first configured CORS origin is the frontend base URL; fall back to local dev. */
    private static String firstOrigin(String allowedOrigins) {
        if (allowedOrigins != null) {
            for (String o : allowedOrigins.split(",")) {
                String t = o.trim();
                if (!t.isEmpty() && !"*".equals(t)) {
                    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
                }
            }
        }
        return "http://localhost:3000";
    }
}
