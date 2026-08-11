package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.billing.dto.PdfAllowanceResponse;
import lombok.Builder;
import lombok.Data;

/**
 * What the studio needs to know after handing over a colour board: what the paying plan
 * has left, what THIS project has left, and whether that board was the last one.
 *
 * {@code closed} is the important one — it is what sends the customer on to pick a combo
 * and render it. The decision is made here rather than in the browser because the count it
 * turns on lives on the project row, and a client that decided for itself could close a
 * project it had merely lost track of.
 */
@Data
@Builder
public class ColourBoardResponse {

    /** The paying plan's monthly download allowance, after this charge. */
    private PdfAllowanceResponse allowance;

    /** Colour boards this project has handed over, and how many it may. */
    private int boardsUsed;
    private int boardsAllowed;

    /** True when this board was the one that closed the project. */
    private boolean closed;
}
