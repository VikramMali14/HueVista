package com.gridstore.huevista.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/** Client-IP resolution shared by the rate-limit filters. */
public final class ClientIps {

    private ClientIps() {}

    /**
     * Real client IP. Forwarded headers are client-controlled, so they are only
     * consulted when {@code trustForwardedHeaders} is set — i.e. when the deployment
     * guarantees the backend sits behind our own proxy that overwrites them.
     */
    public static String clientIp(HttpServletRequest request, boolean trustForwardedHeaders) {
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
}
