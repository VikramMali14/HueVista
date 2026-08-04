package com.gridstore.huevista.lead.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * The public "request a shop account" form.
 *
 * <p>Asks for everything the account needs in one pass — including the password the
 * owner will sign in with, typed twice — so that once the address is verified an
 * admin has nothing left to fill in. Deliberately does NOT ask which plan they want:
 * every shop is created free, and a paid plan is only ever reached by buying one.
 */
@Data
public class ShopLeadRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 120)
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 200)
    private String email;

    @Size(max = 32)
    private String phone;

    @NotBlank(message = "Shop name is required")
    @Size(max = 200)
    private String shopName;

    @Size(max = 120)
    private String city;

    @Size(max = 120)
    private String state;

    /**
     * The password the shop will sign in with. Write-only on the wire and excluded
     * from {@code toString()} — a validation error or a debug log must never be able
     * to echo it back. Hashed the moment it reaches the service; the plaintext is
     * never stored, never emailed and never visible to an admin.
     */
    @NotBlank(message = "Choose a password")
    @Size(min = 8, max = 128, message = "Password must be at least 8 characters")
    @jakarta.validation.constraints.Pattern(
            regexp = "^(?=.*\\p{L})(?=.*\\d).*$",
            message = "Password must contain at least one letter and one number")
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** Typed a second time so a mistyped password can't lock the owner out of their own shop. */
    @NotBlank(message = "Type the password again")
    @Size(max = 128)
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String confirmPassword;

    @Size(max = 2000)
    private String notes;
}
