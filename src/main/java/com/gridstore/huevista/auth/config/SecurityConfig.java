package com.gridstore.huevista.auth.config;

import com.gridstore.huevista.auth.filter.JwtAuthFilter;
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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Collections;

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
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;
    private final JwtAuthFilter jwtAuthFilter;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2FailureHandler;
    private final PasswordEncoder passwordEncoder;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ── CORS ──────────────────────────────────────────────────────
            .cors(cors -> cors.configurationSource(corsConfigurationSource))

            // ── Stateless REST — no CSRF, no sessions ─────────────────────
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ── Route access rules ─────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST,
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/refresh").permitAll()
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                // Shade catalog is public read-only data
                .requestMatchers(HttpMethod.GET, "/api/shades", "/api/shades/**").permitAll()
                .anyRequest().authenticated()
            )

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

            // ── Insert JWT filter BEFORE UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

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
            com.gridstore.huevista.auth.model.User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
            return org.springframework.security.core.userdetails.User.builder()
                    .username(user.getId())
                    .password(user.getPassword() != null ? user.getPassword() : "")
                    .authorities(Collections.emptyList())
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
