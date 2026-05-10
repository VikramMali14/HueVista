package com.gridstore.huevista.auth.config;

import com.gridstore.huevista.auth.filter.JwtAuthFilter;
import com.gridstore.huevista.auth.handler.OAuth2AuthenticationFailureHandler;
import com.gridstore.huevista.auth.handler.OAuth2AuthenticationSuccessHandler;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.CustomOAuth2UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ── Stateless REST — no CSRF, no sessions ─────────────────────
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ── Route access rules ─────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()          // register, login, refresh
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll() // OAuth2 redirect URIs
                .anyRequest().authenticated()
            )

            // ── Google OAuth2 login flow ───────────────────────────────────
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo ->
                    userInfo.userService(customOAuth2UserService))    // upsert user after token exchange
                .successHandler(oAuth2SuccessHandler)                // write JWT JSON response
                .failureHandler(oAuth2FailureHandler)                // write error JSON response
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
        return email -> userRepository.findByEmail(email)
                .map(user -> org.springframework.security.core.userdetails.User.builder()
                        .username(user.getId())
                        .password(user.getPassword() != null ? user.getPassword() : "")
                        .authorities(Collections.emptyList())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    /**
     * DaoAuthenticationProvider:
     *  - calls UserDetailsService.loadUserByUsername(email) to get stored hash
     *  - calls PasswordEncoder.matches(rawPassword, storedHash) to verify
     *  - throws BadCredentialsException on mismatch
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
