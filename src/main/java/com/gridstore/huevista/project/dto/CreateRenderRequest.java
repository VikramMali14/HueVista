package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.ProjectRender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * What to render, and how.
 *
 * Every option but the note is an enum, so the request cannot describe a render the prompt
 * builder has no words for — and, more to the point, cannot smuggle free text into a
 * generative prompt through a field that looks like a setting. The note is the one place
 * the customer writes prose, and it is bounded, escaped and framed as data before it gets
 * anywhere near the model.
 */
@Data
public class CreateRenderRequest {

    /** Which of the project's colour-board combinations to render. */
    @NotBlank(message = "Choose one of your colour-board combinations.")
    private String comboId;

    @NotNull(message = "Choose day or night.")
    private ProjectRender.TimeOfDay timeOfDay;

    @NotNull(message = "Choose whether to keep the existing borders.")
    private ProjectRender.BorderMode borderMode;

    @NotNull(message = "Choose a lighting style.")
    private ProjectRender.Lighting lighting;

    @NotNull(message = "Choose what to do with the furniture.")
    private ProjectRender.Furnishing furnishing;

    @NotNull(message = "Choose a look.")
    private ProjectRender.RenderStyle style;

    /**
     * How good an image to make, and therefore how many credits it costs.
     *
     * <p>The one optional enum on this request. Null reads as BASIC — every render made
     * before the tiers existed was one, and a client that names no quality is asking for
     * the ordinary picture at the ordinary price. Defaulting the other way would charge
     * somebody four credits for saying nothing.
     */
    private ProjectRender.Quality quality;

    @Size(max = 500, message = "Keep the note under 500 characters.")
    private String note;
}
