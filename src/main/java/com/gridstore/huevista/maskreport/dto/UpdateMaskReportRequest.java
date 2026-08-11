package com.gridstore.huevista.maskreport.dto;

import com.gridstore.huevista.maskreport.model.MaskReportStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Admin edit of a queued report. Both fields optional — send either or both. */
@Data
public class UpdateMaskReportRequest {

    /** New status. Null leaves it where it is (note-only edit). */
    private MaskReportStatus status;

    /** Internal note. Null leaves the existing one; blank clears it. */
    @Size(max = 2000, message = "Please keep the note under 2000 characters.")
    private String adminNote;
}
