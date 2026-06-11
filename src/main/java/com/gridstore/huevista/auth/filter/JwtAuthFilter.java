package com.gridstore.huevista.auth.filter;

import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Runs exactly once per HTTP request (OncePerRequestFilter).
 *
 * Responsibility:
 *  1. Extract the Bearer token from the Authorization header.
 *  2. Validate its signature and expiry via JwtService.
 *  3. Load the user from the DB to confirm they still exist.
 *  4. Set a UsernamePasswordAuthenticationToken into SecurityContextHolder
 *     so that downstream filters and controllers know the request is authenticated.
 *
 * This filter does NOT run for /api/auth/** or /oauth2/** — see shouldNotFilter().
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (token != null && jwtService.isTokenValid(token)) {
            String userId = jwtService.extractUserId(token);
            String email  = jwtService.extractEmail(token);

            // Only populate context if not already authenticated
            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                userRepository.findById(userId).ifPresentOrElse(
                        appUser -> {
                            UserRole role = appUser.getRole() != null ? appUser.getRole() : UserRole.CUSTOMER;
                            List<SimpleGrantedAuthority> authorities =
                                    List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));

                            UserDetails userDetails = User.builder()
                                    .username(userId)
                                    .password("")
                                    .authorities(authorities)
                                    .build();

                            UsernamePasswordAuthenticationToken authToken =
                                    new UsernamePasswordAuthenticationToken(
                                            userDetails, null, userDetails.getAuthorities());

                            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                            SecurityContextHolder.getContext().setAuthentication(authToken);
                            log.debug("JWT authenticated user [{}] role={} for {}",
                                    maskEmail(email), role, request.getServletPath());
                        },
                        () -> log.warn("JWT contained unknown userId: {}", userId)
                );
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Masks an email address for log output — emails are PII and must not appear
     * raw in logs, even at DEBUG. Keeps the first two characters of the local part
     * and the full domain: {@code vikram@example.com -> vi****@example.com}.
     */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "<no-email>";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "****";
        }
        String visible = email.substring(0, Math.min(2, at));
        return visible + "****" + email.substring(at);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    /**
     * Skip JWT validation for public auth endpoints — they don't carry a token.
     * Spring Security's permitAll() still applies; this just avoids unnecessary work.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/auth/register")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/refresh")
                || path.startsWith("/oauth2/")
                || path.startsWith("/login/oauth2/");
    }
}
