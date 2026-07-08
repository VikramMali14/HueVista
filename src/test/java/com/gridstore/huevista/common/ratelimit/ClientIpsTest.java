package com.gridstore.huevista.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpsTest {

    @Test
    void takesRightmostXForwardedForHopWithOneTrustedProxy() {
        // "203.0.113.7" is whatever the client typed; "198.51.100.9" was appended
        // by the single trusted proxy — that one wins.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.9");
        req.setRemoteAddr("10.0.0.99");
        assertThat(ClientIps.clientIp(req, true, 1)).isEqualTo("198.51.100.9");
    }

    @Test
    void forgedEntriesCannotRotateTheBucket() {
        // An attacker prepending random IPs must still land in the same bucket:
        // the trusted (rightmost) entry decides.
        for (String forged : new String[]{"1.1.1.1", "2.2.2.2", "3.3.3.3"}) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-Forwarded-For", forged + ", 198.51.100.9");
            req.setRemoteAddr("10.0.0.99");
            assertThat(ClientIps.clientIp(req, true, 1)).isEqualTo("198.51.100.9");
        }
    }

    @Test
    void countsTrustedHopsFromTheRight() {
        // Two trusted proxies: CDN appended the client, LB appended the CDN —
        // the client is the second entry from the end.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7, 10.1.1.1");
        req.setRemoteAddr("10.0.0.99");
        assertThat(ClientIps.clientIp(req, true, 2)).isEqualTo("203.0.113.7");
    }

    @Test
    void singleEntryHeaderIsUsedAsIs() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.7");
        req.setRemoteAddr("10.0.0.99");
        assertThat(ClientIps.clientIp(req, true, 1)).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToXRealIpWhenNoForwardedFor() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "198.51.100.5");
        req.setRemoteAddr("10.0.0.99");
        assertThat(ClientIps.clientIp(req, true, 1)).isEqualTo("198.51.100.5");
    }

    @Test
    void fallsBackToRemoteAddrWhenNoHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("192.0.2.44");
        assertThat(ClientIps.clientIp(req, true, 1)).isEqualTo("192.0.2.44");
    }

    @Test
    void ignoresForwardedHeadersWhenNotTrusted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.7");
        req.addHeader("X-Real-IP", "198.51.100.5");
        req.setRemoteAddr("192.0.2.44");
        assertThat(ClientIps.clientIp(req, false, 1)).isEqualTo("192.0.2.44");
    }
}
