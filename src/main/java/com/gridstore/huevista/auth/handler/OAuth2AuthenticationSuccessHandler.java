package com.gridstore.huevista.auth.handler;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Called by Spring Security after the full OAuth2 code exchange completes and
 * CustomOAuth2UserService has upserted the user into our DB.
 *
 * Issues our own JWT + refresh token, then redirects the browser back to the
 * frontend callback with the tokens in the URL <em>fragment</em>:
 * {@code {frontend}/sign-in/callback#accessToken=...&refreshToken=...&expiresIn=...}.
 * The fragment is never sent to a server, so the tokens stay out of access logs and
 * proxies; the callback page reads them client-side and exchanges them for HttpOnly
 * session cookies (mirrors the email/password login path).
 */
@Component
@Slf4j
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final AuthService authService;
    private final String frontendUrl;

    public OAuth2AuthenticationSuccessHandler(
            UserRepository userRepository,
            AuthService authService,
            @Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.frontendUrl = firstOrigin(allowedOrigins);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Map<String, Object> attrs = oAuth2User.getAttributes();
        String email = (String) attrs.get("email");

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("User not found after OAuth2 login: " + email));

        AuthResponse auth = authService.buildAuthResponse(user);
        log.info("OAuth2 login successful for: {}", email);

        String fragment = "accessToken=" + enc(auth.getAccessToken())
                + "&refreshToken=" + enc(auth.getRefreshToken())
                + "&expiresIn=" + auth.getExpiresIn();
        String target = frontendUrl + "/sign-in/callback#" + fragment;

        // Set the Location header directly so the '#' fragment is preserved verbatim
        // (servlet sendRedirect/encodeRedirectURL can mangle fragments).
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", target);
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

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
