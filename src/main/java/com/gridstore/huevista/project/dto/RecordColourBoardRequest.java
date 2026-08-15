package com.gridstore.huevista.project.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * What was on the colour board the studio just built, sent with the charge for it.
 *
 * The board is assembled entirely in the browser — the server has never seen the pages —
 * so this is the only moment the shades that went onto paper can be captured. Everything
 * downstream of closing depends on it: the combos the customer chooses a render
 * from, and the shades that stay unlocked once the project closes.
 */
@Data
public class RecordColourBoardRequest {

    @NotEmpty(message = "A colour board needs at least one page.")
    @Size(max = 32, message = "That is more pages than any plan allows on one board.")
    @Valid
    private List<Page> pages;

    @Data
    public static class Page {

        @Size(max = 160)
        private String title;

        @NotEmpty(message = "Every page needs at least one colour on it.")
        @Size(max = 16, message = "That is more surfaces than a room has.")
        @Valid
        private List<Shade> shades;
    }

    @Data
    public static class Shade {

        /** The region this colour was on, when the client knows it. */
        private Long regionId;

        @Size(max = 255)
        private String regionLabel;

        @Size(max = 64)
        private String shadeCode;

        @Size(max = 160)
        private String shadeName;

        /**
         * The colour itself, and the one field that cannot be missing: it is what the
         * render pipeline is given, and what the combo is re-drawn from on the selection
         * page. Validated as a hex triple rather than taken on trust — it is interpolated
         * into an AI prompt downstream, and "whatever the client sent" is not something to
         * put in a prompt.
         */
        @NotNull(message = "A colour is required.")
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Colour must be a #rrggbb hex code.")
        private String hex;
    }
}
