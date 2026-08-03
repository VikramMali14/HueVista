package com.gridstore.huevista.newsletter.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Joining the monthly letter. The address is the whole request. */
@Data
public class NewsletterSubscribeRequest {

    @NotBlank(message = "Enter your email address")
    @Email(message = "Enter a valid email address")
    @Size(max = 255, message = "That email address is too long")
    private String email;

    /** Which surface the signup came from — attribution only, never trusted for anything. */
    @Size(max = 60)
    private String source;
}
