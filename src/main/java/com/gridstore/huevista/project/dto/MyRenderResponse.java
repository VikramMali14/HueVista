package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.ProjectPdfPageShade;
import com.gridstore.huevista.project.model.ProjectRender;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One finished AI image, seen from the account rather than from the project.
 *
 * {@link ProjectRenderResponse} answers "what is happening to the image I just asked
 * for?" — it is polled while a render is in flight, so it carries status and a failure
 * reason and belongs to a project the caller already has open. This answers a different
 * question: "where are my images?". A customer who made one, closed the tab and came back
 * a week later had nowhere to look — the picture existed only on the render page of a
 * project they would have to remember by name — so this is the shelf, and it is keyed by
 * the ACCOUNT.
 *
 * <p>That is why it carries the project's name and the combination's shades, neither of
 * which the per-project response needs: on this page there is no project open to read them
 * from, and both are needed to say what the picture is and to print it. Shades in
 * particular are what let a single image be turned into a PDF on its own — a sheet with
 * the picture and no colours on it would be a screenshot, not a colour document.
 */
@Data
@Builder
public class MyRenderResponse {

    private String id;

    /** The room this was made from, so the page can name it and link back to it. */
    private String projectId;
    private String projectName;
    private String roomType;

    /** Always READY on this endpoint — carried so the shape matches the studio's own
     *  render type and one TypeScript interface can serve both. */
    private String status;

    /** Presigned fresh on every read, like every other image URL in the product. */
    private String imageUrl;

    private String timeOfDay;
    private String borderMode;
    private String lighting;
    private String furnishing;
    private String style;
    private String note;

    /** The colour-board combination it was made from, when the page still exists. */
    private String comboId;
    private String comboTitle;
    private Integer boardIndex;

    /**
     * The shades that combination was printed in — the same list, in the same order, as
     * the colour board carried.
     *
     * Empty when the board page has been deleted out from under the render (the row is
     * ON DELETE SET NULL for exactly that reason). The image is still the deliverable and
     * still downloads; only its shade table is missing, so this reads as an empty list
     * rather than as an absent render.
     */
    private List<ProjectComboResponse.Shade> shades;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /**
     * @param hvByCode platform-wide HV codes keyed by upper-cased manufacturer code, so a
     *                 sheet printed from this page carries the same customer-facing
     *                 numbering the original board did. May be empty.
     */
    public static MyRenderResponse from(ProjectRender render, String imageUrl,
                                        Map<String, String> hvByCode) {
        var page = render.getPage();
        var project = render.getProject();
        return MyRenderResponse.builder()
                .id(render.getId())
                .projectId(project.getId())
                .projectName(project.getName())
                .roomType(project.getRoomType())
                .status(render.getStatus().name())
                .imageUrl(imageUrl)
                .timeOfDay(render.getTimeOfDay().name())
                .borderMode(render.getBorderMode().name())
                .lighting(render.getLighting().name())
                .furnishing(render.getFurnishing().name())
                .style(render.getStyle().name())
                .note(render.getNote())
                .comboId(page != null ? page.getId() : null)
                .comboTitle(page != null ? page.getTitle() : null)
                .boardIndex(page != null ? page.getBoardIndex() : null)
                .shades(page == null ? List.of() : page.getShades().stream()
                        .map(s -> shadeOf(s, hvByCode))
                        .toList())
                .createdAt(render.getCreatedAt())
                .completedAt(render.getCompletedAt())
                .build();
    }

    private static ProjectComboResponse.Shade shadeOf(ProjectPdfPageShade s,
                                                      Map<String, String> hvByCode) {
        return ProjectComboResponse.Shade.builder()
                .regionId(s.getRegionId())
                .regionLabel(s.getRegionLabel())
                .shadeCode(s.getShadeCode())
                .shadeName(s.getShadeName())
                .hvCode(s.getShadeCode() == null ? null
                        : hvByCode.get(s.getShadeCode().toUpperCase(Locale.ROOT)))
                .hex(s.getHexCode())
                .build();
    }
}
