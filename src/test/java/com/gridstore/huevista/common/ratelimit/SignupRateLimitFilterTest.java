package com.gridstore.huevista.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SignupRateLimitFilterTest {

    @Test
    void prefersFirstXForwardedForHop() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        req.setRemoteAddr("10.0.0.99");
        assertThat(SignupRateLimitFilter.clientIp(req, true)).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToXRealIpWhenNoForwardedFor() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "198.51.100.5");
        req.setRemoteAddr("10.0.0.99");
        assertThat(SignupRateLimitFilter.clientIp(req, true)).isEqualTo("198.51.100.5");
    }

    @Test
    void fallsBackToRemoteAddrWhenNoHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("192.0.2.44");
        assertThat(SignupRateLimitFilter.clientIp(req, true)).isEqualTo("192.0.2.44");
    }

    @Test
    void ignoresForwardedHeadersWhenNotTrusted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.7");
        req.addHeader("X-Real-IP", "198.51.100.5");
        req.setRemoteAddr("192.0.2.44");
        assertThat(SignupRateLimitFilter.clientIp(req, false)).isEqualTo("192.0.2.44");
    }
}
