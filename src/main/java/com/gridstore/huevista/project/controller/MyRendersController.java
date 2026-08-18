package com.gridstore.huevista.project.controller;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.account.security.RequiresFeature;
import com.gridstore.huevista.project.dto.MyRenderResponse;
import com.gridstore.huevista.project.dto.RenderableProjectResponse;
import com.gridstore.huevista.project.service.ProjectRenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The account's own AI images, gathered from every room it owns.
 *
 * <p>A controller of its own rather than another route on {@link ProjectController},
 * because it is not scoped to a project and the path would collide if it pretended to be:
 * anything hung under {@code /api/projects/…} that is not an id is one careless mapping
 * away from being matched as one. {@code /api/me/…} is where the product already puts
 * "about the signed-in account" reads.
 *
 * <p>No id is accepted and none is needed — the query is scoped by the authenticated
 * owner, so ownership is a property of the query rather than a check in front of it.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Tag(name = "My AI images", description = "Every finished AI image this account owns")
@RequiresFeature(AppFeature.STUDIO)
public class MyRendersController {

    private final ProjectRenderService renderService;

    @Operation(
            summary = "List my AI images",
            description = """
                    Every FINISHED AI image belonging to the signed-in account, newest
                    first, across all of its rooms.

                    Each entry carries the room it was made from and the colour-board
                    combination it was made in — including the shades, with the shop's
                    customer-facing HV codes resolved — so one image can be shown, named
                    and printed on its own without opening the project behind it.

                    Renders that are still queued or running, and ones that failed, are
                    left out: the first are being polled by the studio that asked for
                    them, and the second have already handed their credit back.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The account's finished images")
    @GetMapping("/renders")
    public ResponseEntity<List<MyRenderResponse>> listMyRenders(Authentication auth) {
        return ResponseEntity.ok(renderService.listForOwner(auth.getName()));
    }

    @Operation(
            summary = "Rooms I can make another AI image from",
            description = """
                    The signed-in account's CLOSED projects that handed over a colour
                    board, newest-finished first — the rooms a new AI image can be started
                    from without opening the studio.

                    Both are required. Closed, because this is the "make another of a job
                    you finished" route and an open room is reached from the studio it is
                    open in. Handed over a board, because an image is made FROM a
                    combination, so a room that closed without taking one has nothing to
                    photograph and offering it would dead-end on the next screen.

                    Each entry carries BOTH photographs — the cleaned one and the original
                    — because choosing between them is the next thing asked.
                    `cleanedImageUrl` is null when the room never got one, which is how a
                    client knows not to offer a choice with one real option in it.

                    An image itself is still requested against the project it belongs to
                    (`POST /api/projects/{id}/renders`), and is paid for with an AI credit
                    like every other.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Closed rooms with combinations to render")
    @GetMapping("/renderable-projects")
    public ResponseEntity<List<RenderableProjectResponse>> renderableProjects(Authentication auth) {
        return ResponseEntity.ok(renderService.renderableProjects(auth.getName()));
    }
}
