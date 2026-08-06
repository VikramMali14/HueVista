package com.gridstore.huevista.account.security;

import com.gridstore.huevista.account.service.FeatureAccessService;
import com.gridstore.huevista.account.service.PlanFeatureService;
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
 * <p>Anonymous and guest callers pass straight through: these are constraints placed on a
 * SHOP, resolved from a signed-in retailer's own org and plan. A guest is scoped by their
 * access code and a visitor by the public routes; neither has a shop to look up, and
 * failing them here would break the walk-in flows.
 *
 * <p>Two limits are checked, in the order a shop can act on them. The distributor's grant
 * comes first because only the distributor can lift it; the shop's own PLAN second,
 * because that one the shop can lift itself. A page missing on both counts should say the
 * thing the shop cannot fix.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureGuardInterceptor implements HandlerInterceptor {

    private final FeatureAccessService featureAccessService;
    private final PlanFeatureService planFeatureService;

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
        // Both throw SecurityException, which the app's handler renders as 403 naming the
        // page — so the shop knows what to ask its distributor for, or what its own plan
        // is holding back.
        featureAccessService.assertFeature(auth.getName(), required.value());
        planFeatureService.assertIncluded(auth.getName(), required.value());
        return true;
    }

    private static boolean isAnonymousOrGuest(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .anyMatch(a -> a.equals("ROLE_ANONYMOUS") || a.equals("ROLE_GUEST"));
    }
}
