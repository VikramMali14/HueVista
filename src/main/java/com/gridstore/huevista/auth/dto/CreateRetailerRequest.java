package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** ADMIN or DISTRIBUTOR: create a RETAILER (shop) account with a provisioned org + trial. */
@Data
public class CreateRetailerRequest {

    @NotBlank(message = "Owner name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "An initial password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank(message = "Shop name is required")
    private String shopName;

    private String city;
    private String state;
    private String phone;
    /** "starter" | "pro"/"professional" | "business" — the shop's plan tier. */
    private String tier;

    // ── Access granted at creation time (distributor-created shops) ───────
    //
    // A distributor decides what a shop can reach as part of setting it up, rather
    // than creating an account with the run of the whole product and tightening it
    // afterwards. Both restrictions default to UNRESTRICTED so an admin-created shop
    // — and every existing caller that never sends these fields — behaves exactly as
    // it did before. They are ignored when the new shop has no distributor to grant
    // them (an admin creating an unlinked shop).

    /** Brand ids the shop may work with. Ignored when {@link #brandsUnrestricted}. */
    private List<Long> brandIds = new ArrayList<>();

    /** True = the shop carries every paint company. */
    private boolean brandsUnrestricted = true;

    /** {@code AppFeature} names the shop may open. Ignored when {@link #featuresUnrestricted}. */
    private List<String> features = new ArrayList<>();

    /** True = the shop opens every page. */
    private boolean featuresUnrestricted = true;
}
