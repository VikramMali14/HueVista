package com.gridstore.huevista.auth;

import com.gridstore.huevista.auth.filter.OAuthClientHintFilter;
import com.gridstore.huevista.auth.handler.OAuth2AuthenticationFailureHandler;
import com.gridstore.huevista.auth.handler.OAuthClientHint;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing that makes a Google sign-in work on a phone: the hint that says
 * "the app started this", carried across the trip to Google and back and read by
 * the handler that decides where the exchange code is delivered.
 *
 * Get it wrong in either direction and the failure is silent and total — a phone
 * sent to the website signs the browser in and leaves the app signed out, and a
 * browser sent to `huevista://` lands on a URL nothing can open.
 */
class OAuthMobileRedirectTest {

    private static final String MOBILE_URI = "huevista://sign-in/callback";

    @Test
    void authorizationRequestFromTheAppSetsTheHint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        request.setParameter("client", "mobile");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuthClientHintFilter().doFilter(request, response, new MockFilterChain());

        Cookie cookie = response.getCookie(OAuthClientHint.COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("mobile");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isPositive();
    }

    @Test
    void aWebSignInLeavesNoMobileHintBehind() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        // The browser still carries the cookie from an earlier attempt on this device.
        request.setCookies(new Cookie(OAuthClientHint.COOKIE, "mobile"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuthClientHintFilter().doFilter(request, response, new MockFilterChain());

        Cookie cookie = response.getCookie(OAuthClientHint.COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isZero(); // expired, not inherited
    }

    @Test
    void anUnrecognisedHintIsNotEchoedBack() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        request.setParameter("client", "javascript:alert(1)");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuthClientHintFilter().doFilter(request, response, new MockFilterChain());

        // Nothing to carry: either no cookie at all, or one that expires at once.
        Cookie cookie = response.getCookie(OAuthClientHint.COOKIE);
        assertThat(cookie == null || cookie.getMaxAge() == 0).isTrue();
        assertThat(OAuthClientHint.isMobile(request)).isFalse();
    }

    @Test
    void theFilterIgnoresEverythingButTheAuthorizationRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shades");
        request.setParameter("client", "mobile");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuthClientHintFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getCookie(OAuthClientHint.COOKIE)).isNull();
    }

    @Test
    void aFailedMobileSignInGoesBackToTheApp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setCookies(new Cookie(OAuthClientHint.COOKIE, "mobile"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuth2AuthenticationFailureHandler("https://huevista.org", MOBILE_URI)
                .onAuthenticationFailure(request, response, new BadCredentialsException("denied"));

        assertThat(response.getHeader("Location")).isEqualTo(MOBILE_URI + "#error=google");
    }

    @Test
    void aFailedWebSignInStillGoesBackToTheSite() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuth2AuthenticationFailureHandler("https://huevista.org", MOBILE_URI)
                .onAuthenticationFailure(request, response, new BadCredentialsException("denied"));

        assertThat(response.getHeader("Location")).isEqualTo("https://huevista.org/sign-in?error=google");
    }
}
