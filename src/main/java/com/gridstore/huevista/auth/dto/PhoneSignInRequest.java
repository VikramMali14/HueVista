package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sign in with a mobile number: the Firebase ID token the browser received after
 * Firebase texted a one-time code and the customer typed it back correctly.
 *
 * <p>Note what is NOT here: the phone number. It is read from the signed token's
 * claims and never from the request body — a number the caller supplies is a number
 * the caller chose, and this endpoint hands out a session on the account that owns it.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PhoneSignInRequest {

    @NotBlank(message = "Sign-in token is required")
    @Size(max = 4096, message = "That sign-in token is not valid")
    private String idToken;

    /**
     * What to call the customer, used ONLY when this number has no account yet.
     * Optional — a phone sign-in proves a number and carries no name, so the sign-up
     * screen asks for one and a blank stays blank rather than blocking the way in.
     */
    @Size(max = 100, message = "That name is too long")
    private String name;
}
