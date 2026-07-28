package com.gridstore.huevista.account.security;

import com.gridstore.huevista.account.service.FeatureAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresFeature} — the distributor→shop page grant — on every request
 * that reaches an annotated handler.
 *
 * <p>One interceptor rather than a call at the top of ~40 handlers, for the reason the
 * grant was unenforced in the first place: a rule that has to be remembered per method
 * is a rule that gets forgotten, and the forgetting is silent. Here a missing annotation
 * is visible in the controller, next to the {@code @PreAuthorize} that guards the role.
 *
 * <p>Deliberately runs on the resolved handler rather than by URL pattern. The paths
 * these features gate are spread across several controllers and a couple of them are
 * shared prefixes ({@code /api/organizations/**} covers both the portal and products),
 * so matching by URL would be the kind of second source of truth that drifts.
 *
 * <p>Anonymous and guest callers pass straight through: this is a constraint a
 * distributor places on a SHOP, and it is resolved from a signed-in retailer's own org.
 * A guest is scoped by their access code and a visitor by the public routes; neither has
 * a shop to look up, and failing them here would break the walk-in flows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureGuardInterceptor implements HandlerInterceptor {

    private final FeatureAccessService featureAccessService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresFeature required = AnnotatedElementUtils.findMergedAnnotation(
                method.getMethod(), RequiresFeature.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(
                    method.getBeanType(), RequiresFeature.class);
        }
        if (required == null) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || isAnonymousOrGuest(auth)) {
            return true;
        }
        // Throws SecurityException, which the app's handler renders as 403 naming the
        // page — so the shop knows what to ask its distributor for.
        featureAccessService.assertFeature(auth.getName(), required.value());
        return true;
    }

    private static boolean isAnonymousOrGuest(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .anyMatch(a -> a.equals("ROLE_ANONYMOUS") || a.equals("ROLE_GUEST"));
    }
}
