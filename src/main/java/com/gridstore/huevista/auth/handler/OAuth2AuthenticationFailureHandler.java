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
 * Redirects the browser back to the frontend sign-in page with an error flag
 * instead of dumping raw JSON in the browser.
 */
@Component
@Slf4j
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final String frontendUrl;

    public OAuth2AuthenticationFailureHandler(
            @Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        this.frontendUrl = firstOrigin(allowedOrigins);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("OAuth2 authentication failed: {}", exception.getMessage());
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", frontendUrl + "/sign-in?error=google");
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
