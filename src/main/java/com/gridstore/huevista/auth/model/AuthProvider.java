package com.gridstore.huevista.auth.model;

public enum AuthProvider {
    LOCAL,
    GOOGLE,
    // A passwordless CUSTOMER account auto-provisioned when a walk-in redeems a
    // retailer access code. No credentials — the account is created and signed in
    // in one step; the customer never sets a password.
    ACCESS_CODE
}
