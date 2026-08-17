package com.gridstore.huevista.store.dto;

import lombok.Builder;
import lombok.Data;

/**
 * The answer to "e-mail me a way back in" — deliberately the same answer every time.
 *
 * <p>It carries no hint of whether an account was found, whether a code was sent, or
 * whether the caller is inside the resend cooldown. Any of those would turn this
 * endpoint into a way of asking "has this person shopped here?", which is a question
 * about someone's purchases that a stranger with their address should not get to ask.
 */
@Data
@Builder
public class KioskReentryStatusResponse {

    /** Always true: "if that address bought something here, a code is on its way." */
    @Builder.Default
    private boolean sent = true;

    private int expiresInSeconds;
    private int cooldownSeconds;
}
