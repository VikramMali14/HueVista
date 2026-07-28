package com.gridstore.huevista.account.security;

import com.gridstore.huevista.account.model.AppFeature;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint as belonging to a page a distributor can switch off for one of its
 * shops. Enforced by {@link FeatureGuardInterceptor}.
 *
 * <p>The grant existed as data and as a frontend guard, and nowhere else:
 * {@code FeatureAccessService.assertFeature} had no callers at all, so every endpoint
 * behind a "revoked" page answered normally to a direct request. A shop whose
 * distributor had switched the Customer portal off could still POST an access code; the
 * only thing the revocation did was hide the tab. The comment in the frontend's
 * {@code features.ts} — "the backend enforces the same grant on every endpoint behind
 * these pages" — described an intention, not the code.
 *
 * <p>Annotate the controller (all its handlers) or a single handler method; a
 * method-level annotation wins. Non-retailers are never restricted by this, so applying
 * it to a shared endpoint is safe — see {@link com.gridstore.huevista.account.service.FeatureAccessService}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {

    /** The page whose grant admits this endpoint. */
    AppFeature value();
}
