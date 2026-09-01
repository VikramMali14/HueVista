package com.gridstore.huevista.auth.filter;

import com.gridstore.huevista.auth.handler.OAuthClientHint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Records which client is starting a Google sign-in, before Spring Security's
 * redirect to Google carries the request off our origin.
 *
 * The mobile app opens {@code /oauth2/authorization/google?client=mobile} in a
 * system browser session; everything else is the website. Only the exact value
 * {@code mobile} is honoured — an unrecognised hint is ignored rather than
 * echoed, so the cookie can never carry attacker-chosen text into a redirect.
 *
 * @see OAuthClientHint
 */
@Component
public class OAuthClientHintFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/oauth2/authorization/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (OAuthClientHint.MOBILE.equals(request.getParameter("client"))) {
            OAuthClientHint.remember(response, OAuthClientHint.MOBILE, request.isSecure());
        } else {
            // A second attempt from the website must not inherit the phone's hint.
            OAuthClientHint.clear(request, response);
        }
        chain.doFilter(request, response);
    }
}
