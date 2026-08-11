package com.gridstore.huevista.maskreport.dto;

import com.gridstore.huevista.maskreport.model.MaskReportIssue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** What the studio sends when someone reports a bad AI run. */
@Data
public class CreateMaskReportRequest {

    /**
     * The ticked boxes. At least one is required — a report that names no problem
     * tells the admin nothing and cannot be triaged, and the dialog keeps its submit
     * button disabled until one is on, so an empty list here is a client bug.
     */
    @NotEmpty(message = "Tell us what went wrong — tick at least one option.")
    private List<MaskReportIssue> issues;

    /** Optional detail. Capped so one paste can't become an unbounded column. */
    @Size(max = 2000, message = "Please keep the description under 2000 characters.")
    private String note;
}
