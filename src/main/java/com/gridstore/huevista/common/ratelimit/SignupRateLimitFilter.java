package com.gridstore.huevista.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Fixed-window per-IP rate limiter for the public signup endpoint.
 *
 * Registration is unauthenticated and creates accounts — the prime target for
 * bulk/abusive automation. We cap how many times a single client IP may POST
 * {@code /api/auth/register} within a rolling window using a Redis counter
 * (INCR + EXPIRE). Over the limit → 429 with a Retry-After hint.
 *
 * Notes:
 *  - The browser never calls the backend directly for register; the Next.js
 *    server action forwards the real client IP in {@code X-Forwarded-For}, which
 *    we read here (first hop), falling back to the socket address. This is only
 *    safe while the backend is reachable exclusively through our own frontend —
 *    if the backend port is ever exposed directly, a caller can spoof the header
 *    and rotate "IPs" to bypass the limit. Set
 *    {@code app.rate-limit.trust-forwarded-headers=false} (env
 *    {@code RATE_LIMIT_TRUST_FORWARDED=false}) in such deployments to key the
 *    limiter on the socket address instead.
 *  - Fail-OPEN: if Redis is unreachable we allow the request rather than lock
 *    everyone out — this is defense-in-depth, not the only guard.
 *  - Disabled in tests / local dev via {@code app.rate-limit.enabled=false}
 *    (no Redis there), so it never adds a connection timeout to those runs.
 */
@Component
@Slf4j
public class SignupRateLimitFilter extends OncePerRequestFilter {

    private static final String REGISTER_PATH = "/api/auth/register";
    private static final String KEY_PREFIX = "ratelimit:signup:";

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final boolean trustForwardedHeaders;
    private final int maxAttempts;
    private final Duration window;

    public SignupRateLimitFilter(
            StringRedisTemplate redis,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.trust-forwarded-headers:true}") boolean trustForwardedHeaders,
            @Value("${app.rate-limit.signup.max-attempts:10}") int maxAttempts,
            @Value("${app.rate-limit.signup.window-seconds:3600}") long windowSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !REGISTER_PATH.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = clientIp(request, trustForwardedHeaders);
        String key = KEY_PREFIX + ip;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // First hit in a fresh window — start the TTL.
                redis.expire(key, window);
            }
            if (count != null && count > maxAttempts) {
                log.warn("Signup rate limit hit for ip={} (count={})", ip, count);
                writeTooManyRequests(response);
                return;
            }
        } catch (Exception ex) {
            // Fail-open: never block signups because the limiter backend is down.
            log.warn("Signup rate limiter unavailable ({}) — allowing request", ex.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Real client IP. Forwarded headers are client-controlled, so they are only
     * consulted when {@code trustForwardedHeaders} is set — i.e. when the deployment
     * guarantees the backend sits behind our own proxy that overwrites them.
     */
    static String clientIp(HttpServletRequest request, boolean trustForwardedHeaders) {
        if (trustForwardedHeaders) {
            String fwd = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(fwd)) {
                int comma = fwd.indexOf(',');
                return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
            }
            String real = request.getHeader("X-Real-IP");
            if (StringUtils.hasText(real)) return real.trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(window.toSeconds()));
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\","
                        + "\"message\":\"Too many sign-up attempts from your network. Please wait a while and try again.\","
                        + "\"timestamp\":\"" + LocalDateTime.now() + "\"}");
    }
}
