package com.gridstore.huevista.auth.handler;

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
 * Redirects the browser back to the frontend callback with a SHORT-LIVED,
 * SINGLE-USE exchange code in the URL <em>fragment</em>:
 * {@code {frontend}/sign-in/callback#code=...}. The fragment never reaches a
 * server (stays out of access logs and proxies), and — unlike the tokens this
 * handler used to put there — the code is worthless to anything that reads the
 * URL later (extensions, synced history): it expires in a minute and dies on
 * first use. The callback trades it for real tokens via
 * {@code POST /api/auth/oauth2/exchange} and sets HttpOnly session cookies,
 * mirroring the email/password login path.
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
        String email = com.gridstore.huevista.auth.util.Emails.normalize((String) attrs.get("email"));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("User not found after OAuth2 login: " + email));

        String exchangeCode = authService.createOAuthExchangeCode(user);
        log.info("OAuth2 login successful for: {}", email);

        String target = frontendUrl + "/sign-in/callback#code=" + enc(exchangeCode);

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
