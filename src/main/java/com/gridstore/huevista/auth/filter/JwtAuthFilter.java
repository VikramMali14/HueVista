package com.gridstore.huevista.auth.filter;

import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

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
                boolean exists = userRepository.existsById(userId);

                if (exists) {
                    // Build a minimal UserDetails — authorities are empty (role checks come later)
                    UserDetails userDetails = User.builder()
                            .username(userId)
                            .password("")
                            .authorities(Collections.emptyList())
                            .build();

                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // ← This is the line that marks the request as authenticated
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("JWT authenticated user [{}] for {}", email, request.getServletPath());
                } else {
                    log.warn("JWT contained unknown userId: {}", userId);
                }
            }
        }

        filterChain.doFilter(request, response);
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
        return path.startsWith("/api/auth/") || path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/");
    }
}
