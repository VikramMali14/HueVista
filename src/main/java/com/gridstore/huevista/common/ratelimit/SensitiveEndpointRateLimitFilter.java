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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Per-IP, Redis-backed fixed-window rate limiter for every sensitive
 * UNAUTHENTICATED (and a few cheap-to-abuse authenticated) endpoint:
 *
 *   - register             — bulk account creation
 *   - login                — credential stuffing / password spraying
 *   - refresh              — token grinding
 *   - forgot/reset-password— reset-email bombing + 6-digit OTP brute force
 *   - OTP send (email/sms) — verification-message bombing (cost + spam)
 *   - OTP confirm          — 6-digit verification-code brute force
 *   - access-code redeem   — 8-char code brute force / griefing (burn a shop's code)
 *
 * INCR+EXPIRE fixed window, real client IP from the frontend-forwarded header,
 * 429 + Retry-After when over the limit, and FAIL-OPEN if Redis is unreachable
 * (a limiter outage must never lock legitimate users out of logging in).
 * Disabled with {@code app.rate-limit.enabled=false}.
 */
@Component
@Slf4j
public class SensitiveEndpointRateLimitFilter extends OncePerRequestFilter {

    /** A throttle bucket: how many requests per window, and the Redis key namespace. */
    private record Policy(String name, int maxAttempts, Duration window) {}

    /** A matched rule: METHOD + exact servlet path → Policy. */
    private record Rule(String method, String path, Policy policy) {}

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final boolean trustForwardedHeaders;
    private final List<Rule> rules;

    public SensitiveEndpointRateLimitFilter(
            StringRedisTemplate redis,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.trust-forwarded-headers:true}") boolean trustForwardedHeaders,
            // signup: registration is unauthenticated and creates accounts — the prime
            // target for bulk/abusive automation.
            @Value("${app.rate-limit.signup.max-attempts:10}") int signupMax,
            @Value("${app.rate-limit.signup.window-seconds:3600}") long signupWindow,
            // login: spray defence (per-account lockout already exists; this caps per IP).
            @Value("${app.rate-limit.login.max-attempts:15}") int loginMax,
            @Value("${app.rate-limit.login.window-seconds:300}") long loginWindow,
            // refresh: legitimate clients refresh every ~15 min; allow generous headroom.
            @Value("${app.rate-limit.refresh.max-attempts:60}") int refreshMax,
            @Value("${app.rate-limit.refresh.window-seconds:300}") long refreshWindow,
            // password reset request/confirm: anti-bomb + OTP brute force.
            @Value("${app.rate-limit.password-reset.max-attempts:8}") int resetMax,
            @Value("${app.rate-limit.password-reset.window-seconds:900}") long resetWindow,
            // OTP send (email/phone verification): anti message-bomb (each send costs money).
            @Value("${app.rate-limit.otp-send.max-attempts:6}") int otpSendMax,
            @Value("${app.rate-limit.otp-send.window-seconds:900}") long otpSendWindow,
            // OTP confirm: 6-digit code brute force defence.
            @Value("${app.rate-limit.otp-confirm.max-attempts:12}") int otpConfirmMax,
            @Value("${app.rate-limit.otp-confirm.window-seconds:900}") long otpConfirmWindow,
            // access-code redeem (incl. public guest redeem): 8-char code brute force / griefing.
            @Value("${app.rate-limit.code-redeem.max-attempts:12}") int redeemMax,
            @Value("${app.rate-limit.code-redeem.window-seconds:900}") long redeemWindow,
            // image upload: each authenticated upload triggers a paid Claude Vision
            // classification (~₹0.30) with no quota charge, and a free CUSTOMER account
            // takes seconds to create — cap the burn per IP. A busy counter uploads a
            // handful of photos an hour; 30/h leaves generous headroom.
            @Value("${app.rate-limit.image-upload.max-attempts:30}") int uploadMax,
            @Value("${app.rate-limit.image-upload.window-seconds:3600}") long uploadWindow,
            // shop-account lead form: public write endpoint — anti-spam.
            @Value("${app.rate-limit.lead.max-attempts:5}") int leadMax,
            @Value("${app.rate-limit.lead.window-seconds:3600}") long leadWindow) {
        this.redis = redis;
        this.enabled = enabled;
        this.trustForwardedHeaders = trustForwardedHeaders;

        Policy signup = new Policy("signup", signupMax, Duration.ofSeconds(signupWindow));
        Policy login = new Policy("login", loginMax, Duration.ofSeconds(loginWindow));
        Policy refresh = new Policy("refresh", refreshMax, Duration.ofSeconds(refreshWindow));
        Policy reset = new Policy("pwreset", resetMax, Duration.ofSeconds(resetWindow));
        Policy otpSend = new Policy("otpsend", otpSendMax, Duration.ofSeconds(otpSendWindow));
        Policy otpConfirm = new Policy("otpconfirm", otpConfirmMax, Duration.ofSeconds(otpConfirmWindow));
        Policy redeem = new Policy("redeem", redeemMax, Duration.ofSeconds(redeemWindow));
        Policy upload = new Policy("upload", uploadMax, Duration.ofSeconds(uploadWindow));
        Policy lead = new Policy("lead", leadMax, Duration.ofSeconds(leadWindow));

        this.rules = List.of(
                // Same Redis key namespace ("ratelimit:signup:<ip>") the old dedicated
                // signup filter used, so no window resets across a deploy.
                new Rule("POST", "/api/auth/register", signup),
                new Rule("POST", "/api/auth/login", login),
                // Admin 2FA confirm: 6-digit code brute force defence.
                new Rule("POST", "/api/auth/login/otp", otpConfirm),
                new Rule("POST", "/api/auth/refresh", refresh),
                new Rule("POST", "/api/auth/forgot-password", reset),
                new Rule("POST", "/api/auth/reset-password", reset),
                // SMS reset: the SEND costs money (otp-send bucket); the confirm is a code brute-force (reset bucket).
                new Rule("POST", "/api/auth/forgot-password/phone", otpSend),
                new Rule("POST", "/api/auth/reset-password/phone", reset),
                new Rule("POST", "/api/auth/verify/email/send", otpSend),
                new Rule("POST", "/api/auth/verify/phone/send", otpSend),
                new Rule("POST", "/api/auth/verify/email/confirm", otpConfirm),
                new Rule("POST", "/api/auth/verify/phone/confirm", otpConfirm),
                new Rule("POST", "/api/access-codes/redeem", redeem),
                new Rule("POST", "/api/access-codes/redeem-guest", redeem),
                // Paid-classification / storage-write endpoints.
                new Rule("POST", "/api/images/upload", upload),
                new Rule("POST", "/api/guest/images/upload", upload),
                // Public shop-account request form.
                new Rule("POST", "/api/leads/shop", lead)
        );
    }

    /** Resolve the policy for this request, or null if this path isn't throttled here. */
    private Policy match(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();
        for (Rule r : rules) {
            if (r.method().equalsIgnoreCase(method) && r.path().equals(path)) {
                return r.policy();
            }
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || match(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Policy policy = match(request);
        // shouldNotFilter guarantees policy != null here, but guard defensively.
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = ClientIps.clientIp(request, trustForwardedHeaders);
        String key = KEY_PREFIX + policy.name() + ":" + ip;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, policy.window());
            }
            if (count != null && count > policy.maxAttempts()) {
                log.warn("Rate limit [{}] hit for ip={} path={} (count={})",
                        policy.name(), ip, request.getServletPath(), count);
                writeTooManyRequests(response, policy.window());
                return;
            }
        } catch (Exception ex) {
            // Fail-open: never block auth because the limiter backend is down.
            log.warn("Rate limiter [{}] unavailable ({}) — allowing request", policy.name(), ex.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response, Duration window) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(window.toSeconds()));
        Map<String, Object> body = Map.of(
                "status", 429,
                "error", "Too Many Requests",
                "message", "Too many attempts from your network. Please wait a while and try again.",
                "timestamp", LocalDateTime.now().toString());
        response.getWriter().write(toJson(body));
    }

    /** Minimal hand-rolled JSON to avoid pulling an ObjectMapper into the filter. */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v);
            else sb.append('"').append(String.valueOf(v).replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
