package com.gridstore.huevista.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveEndpointRateLimitFilterTest {

    /** Build a filter with the production default limits; redis is unused by shouldNotFilter(). */
    private SensitiveEndpointRateLimitFilter filter(boolean enabled) {
        return new SensitiveEndpointRateLimitFilter(
                null, enabled, true,
                15, 300,   // login
                60, 300,   // refresh
                8, 900,    // password reset
                6, 900,    // otp send
                12, 900,   // otp confirm
                12, 900,   // code redeem
                30, 3600,  // image upload
                5, 3600,   // shop lead
                60, 3600); // store kiosk order/verify
    }

    private MockHttpServletRequest req(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setMethod(method);
        r.setServletPath(path);
        return r;
    }

    @Test
    void throttlesConfiguredSensitivePaths() {
        SensitiveEndpointRateLimitFilter f = filter(true);
        // shouldNotFilter == false means the limiter WILL act on this request.
        assertThat(f.shouldNotFilter(req("POST", "/api/auth/login"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/auth/refresh"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/auth/forgot-password"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/auth/reset-password"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/auth/verify/email/send"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/auth/verify/phone/confirm"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/access-codes/redeem"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/access-codes/redeem-guest"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/images/upload"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/guest/images/upload"))).isFalse();
        // Store kiosk rules carry a one-segment wildcard for the slug.
        assertThat(f.shouldNotFilter(req("POST", "/api/store/mehta-paints-x7k2p9/order"))).isFalse();
        assertThat(f.shouldNotFilter(req("POST", "/api/store/mehta-paints-x7k2p9/verify"))).isFalse();
    }

    @Test
    void ignoresUnlistedPathsAndWrongMethods() {
        SensitiveEndpointRateLimitFilter f = filter(true);
        assertThat(f.shouldNotFilter(req("GET", "/api/auth/login"))).isTrue();       // wrong method
        assertThat(f.shouldNotFilter(req("POST", "/api/auth/register"))).isTrue();   // owned by SignupRateLimitFilter
        assertThat(f.shouldNotFilter(req("POST", "/api/projects"))).isTrue();        // not a sensitive path
        assertThat(f.shouldNotFilter(req("GET", "/api/shades"))).isTrue();
        // The kiosk wildcard is one segment only — deeper paths don't match.
        assertThat(f.shouldNotFilter(req("GET", "/api/store/some-slug"))).isTrue();
        assertThat(f.shouldNotFilter(req("POST", "/api/store/a/b/order"))).isTrue();
    }

    @Test
    void masterSwitchDisablesAll() {
        SensitiveEndpointRateLimitFilter f = filter(false);
        assertThat(f.shouldNotFilter(req("POST", "/api/auth/login"))).isTrue();
        assertThat(f.shouldNotFilter(req("POST", "/api/auth/reset-password"))).isTrue();
    }
}
