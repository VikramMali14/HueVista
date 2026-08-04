package com.gridstore.huevista.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * ADMIN or DISTRIBUTOR: create a RETAILER (shop) account with a provisioned org.
 *
 * <p>No plan is chosen here. Every shop is created on the free tier and buys a paid
 * plan later if it wants one — so there is no tier to ask for, and no way for a
 * creation form to hand out paid quota.
 */
@Data
public class CreateRetailerRequest {

    @NotBlank(message = "Owner name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    /**
     * Write-only in both directions: never serialized back out, never in
     * {@code toString()}. It is hashed on arrival and the plaintext is not retained.
     */
    @NotBlank(message = "An initial password is required")
    @Size(min = 8, max = 128, message = "Password must be at least 8 characters")
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @NotBlank(message = "Shop name is required")
    private String shopName;

    private String city;
    private String state;
    private String phone;

    /**
     * ADMIN only: the distributor organization the new shop belongs under. Blank
     * files it under the house distributor, so no shop is ever left outside the
     * network. Ignored when a DISTRIBUTOR is the one creating — their shops always
     * land under their own org.
     */
    private String distributorOrgId;

    // ── Access granted at creation time (distributor-created shops) ───────
    //
    // A distributor decides what a shop can reach as part of setting it up, rather
    // than creating an account with the run of the whole product and tightening it
    // afterwards. Both restrictions default to UNRESTRICTED so an admin-created shop
    // — and every existing caller that never sends these fields — behaves exactly as
    // it did before.

    /** Brand ids the shop may work with. Ignored when {@link #brandsUnrestricted}. */
    private List<Long> brandIds = new ArrayList<>();

    /** True = the shop carries every paint company. */
    private boolean brandsUnrestricted = true;

    /** {@code AppFeature} names the shop may open. Ignored when {@link #featuresUnrestricted}. */
    private List<String> features = new ArrayList<>();

    /** True = the shop opens every page. */
    private boolean featuresUnrestricted = true;
}
