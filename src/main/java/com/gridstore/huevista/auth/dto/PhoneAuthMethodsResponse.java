package com.gridstore.huevista.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Which way of proving a mobile number this deployment can actually offer.
 *
 * <p>Read by the sign-in page on the server, so the choice is made once from the
 * backend's own configuration. The alternative — a second copy of the same setting in
 * the frontend's environment — is exactly how a site ends up offering a sign-in its
 * backend answers 503 to.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PhoneAuthMethodsResponse {

    /** {@code FIREBASE}, {@code SMS} or {@code NONE}. */
    private String method;

    /** False when mobile sign-in is off entirely; the page then hides the option. */
    private boolean enabled;
}
