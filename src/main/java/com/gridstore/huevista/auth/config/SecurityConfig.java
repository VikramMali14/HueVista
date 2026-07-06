package com.gridstore.huevista.auth.config;

import com.gridstore.huevista.auth.filter.GuestAuthFilter;
import com.gridstore.huevista.auth.filter.JwtAuthFilter;
import com.gridstore.huevista.common.ratelimit.SensitiveEndpointRateLimitFilter;
import com.gridstore.huevista.common.ratelimit.SignupRateLimitFilter;
import com.gridstore.huevista.auth.handler.OAuth2AuthenticationFailureHandler;
import com.gridstore.huevista.auth.handler.OAuth2AuthenticationSuccessHandler;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.CustomOAuth2UserService;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

/**
 * Central Spring Security configuration.
 *
 * Filter chain order (top → bottom = first → last):
 *
 *   [Servlet Container]
 *        ↓
 *   DisableEncodeUrlFilter          (Spring Security default)
 *   WebAsyncManagerIntegrationFilter
 *   SecurityContextHolderFilter
 *   HeaderWriterFilter
 *   CorsFilter
 *   CsrfFilter                     (disabled — stateless API)
 *   LogoutFilter
 *   ──────────────────────────────────────────────────────
 *   JwtAuthFilter          ← our custom filter inserted HERE
 *   ──────────────────────────────────────────────────────
 *   UsernamePasswordAuthenticationFilter
 *   OAuth2AuthorizationRequestRedirectFilter  (oauth2Login)
 *   OAuth2LoginAuthenticationFilter           (oauth2Login)
 *   ...
 *   ExceptionTranslationFilter
 *   AuthorizationFilter
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;
    private final JwtAuthFilter jwtAuthFilter;
    private final GuestAuthFilter guestAuthFilter;
    private final SignupRateLimitFilter signupRateLimitFilter;
    private final SensitiveEndpointRateLimitFilter sensitiveEndpointRateLimitFilter;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2FailureHandler;
    private final PasswordEncoder passwordEncoder;
    private final CorsConfigurationSource corsConfigurationSource;

    /**
     * When true, /actuator/prometheus is served anonymously (the endpoint must ALSO
     * be exposed via ACTUATOR_EXPOSURE=health,prometheus). Scrapers can't do a JWT
     * dance, so this boolean is the pragmatic gate — only enable it when the backend
     * port is reachable solely from a private network.
     */
    @org.springframework.beans.factory.annotation.Value("${app.metrics.public:false}")
    private boolean metricsPublic;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ── CORS ──────────────────────────────────────────────────────
            .cors(cors -> cors.configurationSource(corsConfigurationSource))

            // ── Stateless REST — no CSRF, no sessions ─────────────────────
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ── Security headers (API responses) ──────────────────────────
            .headers(headers -> headers
                .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults()) // X-Content-Type-Options: nosniff
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
            )

            // ── Route access rules ─────────────────────────────────────────
            .authorizeHttpRequests(auth -> {
                // Prometheus scrape endpoint — anonymous ONLY when explicitly opened
                // (private-network deployments); otherwise it stays authenticated
                // even if ACTUATOR_EXPOSURE exposes it.
                if (metricsPublic) {
                    auth.requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll();
                }
                auth
                .requestMatchers(HttpMethod.POST,
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/api/auth/forgot-password",
                        "/api/auth/forgot-password/phone",
                        "/api/auth/reset-password",
                        "/api/auth/reset-password/phone",
                        // One-time OAuth code -> tokens; the exchange IS the login.
                        "/api/auth/oauth2/exchange").permitAll()
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                // Swagger UI / OpenAPI spec — public
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                // Health probe for load balancers / container healthchecks. Only the
                // status is exposed (show-details=never); no other actuator endpoint
                // is web-exposed at all.
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                // Shade catalog — public read-only
                .requestMatchers(HttpMethod.GET, "/api/shades", "/api/shades/**").permitAll()
                // Shared project view — public, no auth
                .requestMatchers(HttpMethod.GET, "/api/share/**").permitAll()
                // Anonymous guest redemption of a shop access code — issues a guest token
                .requestMatchers(HttpMethod.POST, "/api/access-codes/redeem-guest").permitAll()
                // Public "request a shop account" lead form — per-IP rate-limited
                .requestMatchers(HttpMethod.POST, "/api/leads/shop").permitAll()
                // Guest-scoped endpoints (image upload + project create/recolour) — guests only
                .requestMatchers("/api/guest/**").hasRole("GUEST")
                // Razorpay webhook — no user auth, signature-verified in service
                .requestMatchers(HttpMethod.POST, "/api/billing/webhooks/**").permitAll()
                // Channel webhooks (WhatsApp, ElevenLabs) — called by external
                // providers, not the user; verified by token/secret per channel.
                .requestMatchers("/api/support/webhooks/**").permitAll()
                // Admin endpoints — ROLE_ADMIN only
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated();
            })

            // ── Google OAuth2 login flow ───────────────────────────────────
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo ->
                    userInfo.userService(customOAuth2UserService))    // upsert user after token exchange
                .successHandler(oAuth2SuccessHandler)                // write JWT JSON response
                .failureHandler(oAuth2FailureHandler)                // write error JSON response
            )

            // ── Return 401 JSON for unauthenticated API requests ──────────
            // Without this, Spring Security redirects to Google OAuth2 login page
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication required.\"}");
                })
            )

            // ── Wire in our authentication provider ───────────────────────
            .authenticationProvider(authenticationProvider())

            // ── Guest auth is inserted (just) before the JWT user filter. Both sit
            //    before UsernamePasswordAuthenticationFilter — a custom filter class
            //    can't be used as a position reference, so we anchor to that core
            //    filter. Order between the two doesn't affect correctness: JwtAuthFilter
            //    ignores a guest token (no user has the code's id), and each filter only
            //    acts when the context is still empty.
            .addFilterBefore(guestAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // ── Insert JWT filter BEFORE UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // ── Per-IP signup throttle (only acts on POST /api/auth/register).
            //    It and JwtAuthFilter never both act on one request, so their
            //    relative order doesn't matter.
            .addFilterBefore(signupRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            // ── Per-IP throttle for the remaining sensitive endpoints (login, refresh,
            //    password reset, OTP send/confirm, access-code redeem). Acts only on
            //    those exact paths; fail-open if Redis is down.
            .addFilterBefore(sensitiveEndpointRateLimitFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Loads user by email (used during local login authentication).
     * Subject stored in JWT is userId (UUID), but DaoAuthenticationProvider
     * looks up by username which we map to email here.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return email -> {
            com.gridstore.huevista.auth.model.User user = userRepository
                    .findByEmail(com.gridstore.huevista.auth.util.Emails.normalize(email))
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
            return org.springframework.security.core.userdetails.User.builder()
                    .username(user.getId())
                    .password(user.getPassword() != null ? user.getPassword() : "")
                    .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                    .build();
        };
    }

    // Spring Security 6.x: DaoAuthenticationProvider requires UserDetailsService in constructor
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

}
