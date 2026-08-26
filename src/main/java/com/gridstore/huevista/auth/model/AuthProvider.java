package com.gridstore.huevista.auth.model;

public enum AuthProvider {
    LOCAL,
    GOOGLE,
    // A passwordless CUSTOMER account auto-provisioned when a walk-in redeems a
    // retailer access code. No credentials — the account is created and signed in
    // in one step; the customer never sets a password.
    ACCESS_CODE,
    // A passwordless account whose identity IS a mobile number, proved by an SMS
    // one-time code that Firebase Phone Auth sent and checked. The account may hold
    // no e-mail address at all — see Emails.syntheticForPhone.
    PHONE
}
