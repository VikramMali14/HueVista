package com.gridstore.huevista.auth.dto;

import lombok.Data;

@Data
public class SendPhoneCodeRequest {

    /** Mobile number to verify (with country code). Optional if one is already on file. */
    private String phoneNumber;
}
