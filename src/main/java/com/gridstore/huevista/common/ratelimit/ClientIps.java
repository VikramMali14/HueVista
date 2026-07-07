package com.gridstore.huevista.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/** Client-IP resolution shared by the rate-limit filters. */
public final class ClientIps {

    private ClientIps() {}

    /**
     * Real client IP. Forwarded headers are client-controlled, so they are only
     * consulted when {@code trustForwardedHeaders} is set — i.e. when the deployment
     * guarantees the backend is only reachable through our own frontend/proxy.
     *
     * <p>Trust counts from the RIGHT of {@code X-Forwarded-For}: whatever the client
     * sends arrives as the leftmost entries, and each proxy in the chain APPENDS the
     * address it actually saw. {@code trustedProxyHops} is the number of proxies in
     * front of this backend that append to (or set) the header — default 1, the
     * Next.js frontend, which normalises the header before forwarding. Taking the
     * FIRST entry (as this code once did) would let an attacker rotate a forged IP
     * per request and sail past every per-IP limit.
     */
    public static String clientIp(HttpServletRequest request, boolean trustForwardedHeaders,
                                  int trustedProxyHops) {
        if (trustForwardedHeaders) {
            String fwd = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(fwd)) {
                String[] parts = fwd.split(",");
                int idx = Math.max(0, parts.length - Math.max(1, trustedProxyHops));
                String candidate = parts[idx].trim();
                if (!candidate.isEmpty()) return candidate;
            }
            String real = request.getHeader("X-Real-IP");
            if (StringUtils.hasText(real)) return real.trim();
        }
        return request.getRemoteAddr();
    }
}
