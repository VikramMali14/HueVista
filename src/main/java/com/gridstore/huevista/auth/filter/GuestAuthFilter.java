package com.gridstore.huevista.auth.filter;

import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
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
 * Authenticates anonymous GUEST sessions — a walk-in customer who redeemed a shop
 * access code without creating an account. Runs BEFORE {@link JwtAuthFilter}.
 *
 * A guest token is a normal JWT signed with the same key but carrying
 * {@code scope=guest} and subject = the access code id. If present and valid (and
 * the code still exists and hasn't expired), we set an authentication whose
 * principal is the access code id and whose authority is ROLE_GUEST. Guest-scoped
 * endpoints (/api/guest/**) read that principal to own images/projects by code.
 *
 * For a normal user token (no guest scope) this filter no-ops and lets
 * JwtAuthFilter handle it. Once we set auth here, JwtAuthFilter skips (it only acts
 * when the context is still empty), so its user lookup never runs for the code id.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GuestAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomerAccessCodeRepository accessCodeRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);

        if (token != null
                && SecurityContextHolder.getContext().getAuthentication() == null
                && jwtService.isTokenValid(token)
                && "guest".equals(jwtService.extractScope(token))) {

            String accessCodeId = jwtService.extractUserId(token); // subject
            // Defence in depth: the code must still exist and be within its window,
            // even if the (long-lived) token hasn't technically expired yet.
            accessCodeRepository.findById(accessCodeId)
                    .filter(code -> !code.isExpired())
                    .ifPresentOrElse(code -> {
                        List<SimpleGrantedAuthority> authorities =
                                List.of(new SimpleGrantedAuthority("ROLE_GUEST"));
                        UserDetails principal = User.builder()
                                .username(accessCodeId)
                                .password("")
                                .authorities(authorities)
                                .build();
                        UsernamePasswordAuthenticationToken authToken =
                                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                        log.debug("Guest authenticated for access code {} on {}", accessCodeId, request.getServletPath());
                    }, () -> log.debug("Guest token references an unknown/expired access code: {}", accessCodeId));
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
}
