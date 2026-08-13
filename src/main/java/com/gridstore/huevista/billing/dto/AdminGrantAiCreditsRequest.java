package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** An administrator putting AI image credits into someone's wallet without a payment. */
@Data
public class AdminGrantAiCreditsRequest {

    @NotNull
    @Min(value = 1, message = "Say how many AI image credits to give")
    @Max(value = 500, message = "Give at most 500 AI image credits at a time")
    private Integer credits;

    /** What the holder sees on their statement. Kept short — it is a line, not a note. */
    @Size(max = 160)
    private String reason;
}
