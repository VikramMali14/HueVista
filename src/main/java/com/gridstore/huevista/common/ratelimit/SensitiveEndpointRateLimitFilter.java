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
 *   - phone sign-in        — grinding the Firebase token exchange
 *   - refresh              — token grinding
 *   - forgot/reset-password— reset-email bombing + 6-digit OTP brute force
 *   - OTP send (email/sms) — verification-message bombing (cost + spam)
 *   - OTP confirm          — 6-digit verification-code brute force
 *   - access-code redeem   — 8-char code brute force / griefing (burn a shop's code)
 *   - subscribe / verify   — gateway subscription spam + Checkout-payload replay
 *   - gallery room start   — free project creation (authenticated, but charges nothing)
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

    /**
     * A matched rule: METHOD + servlet path → Policy. The path is usually exact;
     * a single {@code *} matches exactly one path segment (for public endpoints
     * with an id in the middle, e.g. {@code /api/store/*&#47;order}).
     */
    private record Rule(String method, String path, Policy policy) {
        boolean matches(String requestMethod, String requestPath) {
            if (!method.equalsIgnoreCase(requestMethod)) return false;
            int star = path.indexOf('*');
            if (star < 0) return path.equals(requestPath);
            String head = path.substring(0, star);
            String tail = path.substring(star + 1);
            if (requestPath.length() < head.length() + tail.length() + 1) return false;
            if (!requestPath.startsWith(head) || !requestPath.endsWith(tail)) return false;
            // The wildcard covers ONE segment — no slashes inside it.
            return requestPath.substring(head.length(), requestPath.length() - tail.length()).indexOf('/') < 0;
        }
    }

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final boolean trustForwardedHeaders;
    private final int trustedProxyHops;
    private final List<Rule> rules;

    public SensitiveEndpointRateLimitFilter(
            StringRedisTemplate redis,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.trust-forwarded-headers:true}") boolean trustForwardedHeaders,
            // How many proxies in front of this backend append to X-Forwarded-For
            // (trust counts from the right; see ClientIps).
            @Value("${app.rate-limit.trusted-proxy-hops:1}") int trustedProxyHops,
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
            // AI render: the single most expensive call in the product (~$0.10 of Nano
            // Banana Pro per image), and the only one a signed-in user can trigger in a
            // loop. The project's own render allowance is the real limit — this only has
            // to stop a script hammering the endpoint past it, since every refused
            // request still costs a database round trip and a 402. Twelve an hour is far
            // more than any one customer's project can legitimately consume.
            @Value("${app.rate-limit.render.max-attempts:12}") int renderMax,
            @Value("${app.rate-limit.render.window-seconds:3600}") long renderWindow,
            // shop-account lead form: public write endpoint — anti-spam.
            @Value("${app.rate-limit.lead.max-attempts:5}") int leadMax,
            @Value("${app.rate-limit.lead.window-seconds:3600}") long leadWindow,
            // newsletter sign-up: public, and each new address sends a welcome mail — so
            // it is a free way to post mail to strangers unless it is capped. A shop or a
            // household behind one IP might sign up a handful of people; 10/h leaves that
            // alone and gives a script nowhere to go.
            @Value("${app.rate-limit.newsletter.max-attempts:10}") int newsletterMax,
            @Value("${app.rate-limit.newsletter.window-seconds:3600}") long newsletterWindow,
            // public store kiosk order/verify: many legitimate customers can share one
            // shop IP (the kiosk device), so the cap is generous — it only has to stop
            // scripted Razorpay-order spam, not a queue of paying walk-ins.
            @Value("${app.rate-limit.store-order.max-attempts:60}") int storeOrderMax,
            @Value("${app.rate-limit.store-order.window-seconds:3600}") long storeOrderWindow,
            // subscription create/verify: each create opens a real Razorpay subscription
            // (and, with customer_notify, a live payment link), and verify is where a
            // stored Checkout payload would be replayed. A shop subscribes once a month;
            // 20/h is nothing but leaves scripted abuse nowhere to go.
            @Value("${app.rate-limit.subscription.max-attempts:20}") int subscriptionMax,
            @Value("${app.rate-limit.subscription.window-seconds:3600}") long subscriptionWindow,
            // checkout telemetry: public, and a shared kiosk or office IP legitimately
            // reports several events per attempt (opened, then closed or refused) across
            // several attempts. Generous enough never to drop a real report — losing those
            // silently corrupts the very report they feed — while capping a script that
            // wants to fill the payment audit with invented rows.
            @Value("${app.rate-limit.attempt-event.max-attempts:240}") int attemptEventMax,
            @Value("${app.rate-limit.attempt-event.window-seconds:3600}") long attemptEventWindow,
            // gallery "paint this room": authenticated, and each call is only a handful of
            // rows — but it is the one project-creating endpoint that costs the caller
            // nothing at all, so a script could otherwise fill the projects table for free.
            // A visitor browsing the gallery opens a few rooms; 40/h leaves that alone.
            @Value("${app.rate-limit.free-start.max-attempts:40}") int freeStartMax,
            @Value("${app.rate-limit.free-start.window-seconds:3600}") long freeStartWindow) {
        this.redis = redis;
        this.enabled = enabled;
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.trustedProxyHops = trustedProxyHops;

        Policy signup = new Policy("signup", signupMax, Duration.ofSeconds(signupWindow));
        Policy login = new Policy("login", loginMax, Duration.ofSeconds(loginWindow));
        Policy refresh = new Policy("refresh", refreshMax, Duration.ofSeconds(refreshWindow));
        Policy reset = new Policy("pwreset", resetMax, Duration.ofSeconds(resetWindow));
        Policy otpSend = new Policy("otpsend", otpSendMax, Duration.ofSeconds(otpSendWindow));
        Policy otpConfirm = new Policy("otpconfirm", otpConfirmMax, Duration.ofSeconds(otpConfirmWindow));
        Policy redeem = new Policy("redeem", redeemMax, Duration.ofSeconds(redeemWindow));
        Policy upload = new Policy("upload", uploadMax, Duration.ofSeconds(uploadWindow));
        Policy lead = new Policy("lead", leadMax, Duration.ofSeconds(leadWindow));
        Policy newsletter = new Policy("newsletter", newsletterMax, Duration.ofSeconds(newsletterWindow));
        Policy storeOrder = new Policy("storeorder", storeOrderMax, Duration.ofSeconds(storeOrderWindow));
        Policy subscription = new Policy("subscription", subscriptionMax, Duration.ofSeconds(subscriptionWindow));
        Policy attemptEvent = new Policy("attemptevent", attemptEventMax, Duration.ofSeconds(attemptEventWindow));
        Policy render = new Policy("render", renderMax, Duration.ofSeconds(renderWindow));
        Policy freeStart = new Policy("freestart", freeStartMax, Duration.ofSeconds(freeStartWindow));

        this.rules = List.of(
                // Same Redis key namespace ("ratelimit:signup:<ip>") the old dedicated
                // signup filter used, so no window resets across a deploy.
                new Rule("POST", "/api/auth/register", signup),
                new Rule("POST", "/api/auth/login", login),
                // Admin 2FA confirm: 6-digit code brute force defence.
                new Rule("POST", "/api/auth/login/otp", otpConfirm),
                // Mobile sign-in. The SMS it rests on was sent, paid for and throttled by
                // Firebase before this endpoint is ever reached, so what is capped here is
                // grinding at the exchange itself — same shape as a password login, so the
                // same bucket. Account creation rides this path too, but each new account
                // still costs the caller a real SMS to a real handset, which is a far
                // harder limit than anything counted in Redis.
                new Rule("POST", "/api/auth/phone/firebase", login),
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
                // Kiosk re-entry. The send costs an email and can be aimed at somebody
                // else's inbox, so it sits in the otp-send bucket; the confirm is a
                // 6-digit brute force and sits with the other code confirmations.
                new Rule("POST", "/api/store/re-entry", otpSend),
                new Rule("POST", "/api/store/re-entry/confirm", otpConfirm),
                // Paid-classification / storage-write endpoints.
                new Rule("POST", "/api/images/upload", upload),
                new Rule("POST", "/api/guest/images/upload", upload),
                // AI render (project id in the middle) — the priciest call we make.
                new Rule("POST", "/api/projects/*/renders", render),
                // Public shop-account request form. The resend costs an email, so it
                // sits in the otp-send bucket; the confirm is a 6-digit brute force and
                // sits with the other code confirmations.
                new Rule("POST", "/api/leads/shop", lead),
                new Rule("POST", "/api/leads/shop/*/resend", otpSend),
                new Rule("POST", "/api/leads/shop/*/verify", otpConfirm),
                // Public monthly-letter sign-up (each new address sends a welcome mail).
                new Rule("POST", "/api/newsletter/subscribe", newsletter),
                new Rule("POST", "/api/newsletter/unsubscribe", newsletter),
                // Public store kiosk payment endpoints (slug in the middle).
                new Rule("POST", "/api/store/*/order", storeOrder),
                new Rule("POST", "/api/store/*/verify", storeOrder),
                // Gateway-subscription creation and the Checkout-payload verify.
                new Rule("POST", "/api/billing/subscriptions", subscription),
                new Rule("POST", "/api/billing/subscriptions/verify", subscription),
                // Public checkout telemetry (Razorpay reference in the middle).
                new Rule("POST", "/api/billing/attempts/*/events", attemptEvent),
                // Gallery "paint this room" (slug in the middle). Signed-in, but free —
                // nothing is charged for it, so the cap is what bounds it.
                new Rule("POST", "/api/free-projects/*/start", freeStart)
        );
    }

    /** Resolve the policy for this request, or null if this path isn't throttled here. */
    private Policy match(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();
        for (Rule r : rules) {
            if (r.matches(method, path)) {
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

        String ip = ClientIps.clientIp(request, trustForwardedHeaders, trustedProxyHops);
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
