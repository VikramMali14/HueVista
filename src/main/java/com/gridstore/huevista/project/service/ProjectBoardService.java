package com.gridstore.huevista.project.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.billing.dto.PdfAllowanceResponse;
import com.gridstore.huevista.billing.service.PdfQuotaService;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.project.dto.ColourBoardResponse;
import com.gridstore.huevista.project.dto.ProjectComboResponse;
import com.gridstore.huevista.project.dto.RecordColourBoardRequest;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectPdfPage;
import com.gridstore.huevista.project.model.ProjectPdfPageShade;
import com.gridstore.huevista.project.repository.ProjectPdfPageRepository;
import com.gridstore.huevista.project.repository.ProjectRenderRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Colour boards: charging for one, recording what was on it, and closing the project when
 * the last one has been handed over.
 *
 * The order of the three steps is the whole behaviour, and it is the reverse of the
 * obvious one. The QUOTA is reserved first, because that is the step that can refuse — it
 * throws 402 when the paying plan is spent — and a refusal has to happen before anything
 * is written down. Only then are the pages recorded, and only then is the count that can
 * close the project moved. Recording first would leave a project that closed itself on a
 * board its owner was never allowed to download.
 *
 * <p>Closing is decided here rather than in the browser. The count lives on the project
 * row, and a client that decided for itself would close a project every time it lost track
 * of how many boards it had already taken — including on a reload.
 *
 * <p>How many boards a project gets depends on WHO owns it — see {@link #boardsAllowedFor}.
 * A shop works a room over several sheets; a customer bought one job that ends with one
 * board and the render behind it. Both numbers are configuration, and both are served to
 * the studio rather than printed there.
 *
 * <p>One honest gap, inherited from the charge call this replaces: the studio still fails
 * OPEN on a network error, handing over the board without reaching the server. Such a board
 * is neither charged nor recorded, and does not move the project towards closing.
 * Undercounting is the direction to be wrong in — a customer at a counter should not lose
 * their board to a flaky connection — but it does mean the count is of boards we KNOW about,
 * not of boards that exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectBoardService {

    private final ProjectRepository projectRepository;
    private final ProjectPdfPageRepository pageRepository;
    private final ProjectRenderRepository renderRepository;
    private final ProjectAccessService projectAccessService;
    private final PdfQuotaService pdfQuotaService;
    private final com.gridstore.huevista.paint.service.ShadeDecodeService shadeDecodeService;

    /**
     * How many colour boards one project hands over before it closes itself.
     *
     * <p>Four. One was the number when a board WAS the job — pick the colours, take the
     * sheet, done — and it made the cap invisible, because nobody meets a limit they hit
     * on their first try. What people actually do with a room is compare: the greens on
     * one board, the greys on another, and a third for the pair somebody at home
     * preferred. At a cap of one, the second of those was a closed project and a ₹99
     * reopen, so the natural way to use the product was the way that charged for it.
     *
     * <p>Four is enough for that comparison and still finite, which is what keeps the
     * close meaningful: a project that never closes never unlocks its render either.
     * Configuration, so it can be moved without a deploy — and the studio reads the
     * number off the API rather than printing its own copy, so a change here shows up in
     * the sentence the customer reads.
     */
    @Value("${app.project.colour-boards-per-project:4}")
    private int boardsPerProject;

    /**
     * How many colour boards a CUSTOMER's project hands over before it closes itself.
     *
     * <p>One. The four above is a SHOP's number, and it is the right one there: a counter
     * works a room over several passes, prints the greens for one visitor and the greys for
     * the next, and closing on the first sheet would end the job in the middle of the
     * conversation it exists to support.
     *
     * <p>A customer is the other half of that transaction and buys the opposite thing. They
     * bought ONE project — at the till, or through the shop's code — and what they take away
     * is one sheet with their colours on it and, once the project closes, the AI render that
     * closing unlocks. Four boards there did not buy four conversations, it just deferred the
     * ending: the render stayed locked behind a cap the customer had no reason to spend, and
     * the natural way to finish the job was to press a Close button rather than to finish it.
     * At one, downloading the board IS finishing, and the render is waiting on the other side
     * of it.
     *
     * <p>Configuration, like its shop-side twin, so the two can be repriced independently
     * without a deploy — and the studio reads whichever number applies off the API rather
     * than printing its own copy.
     */
    @Value("${app.project.customer-boards-per-project:1}")
    private int customerBoardsPerProject;

    /**
     * Charge for a colour board, record what was on it, and close the project if that was
     * the last one it had.
     *
     * @param billed how to reserve the download against whoever is paying — the account
     *               holder's plan, or a guest's code. Passed in rather than resolved here
     *               because the two callers already know which they are, and the choice
     *               between them is an authentication fact, not a project one.
     */
    @Transactional
    public ColourBoardResponse recordBoard(Project project, RecordColourBoardRequest request,
                                           java.util.function.Supplier<PdfAllowanceResponse> billed) {
        return recordBoard(project, request, billed, true);
    }

    /**
     * @param mayClose whether running out of boards should CLOSE this project.
     *
     * False for a guest room, and the reason is that closing has to leave somewhere to go.
     * What closing buys is the render, and the render page is behind a sign-in a walk-in on
     * a shop's code does not have — so closing one of their rooms would take the studio away
     * and hand back nothing. Their boards are already capped by the code's own allowance,
     * which is the limit that was actually sold to them.
     */
    @Transactional
    public ColourBoardResponse recordBoard(Project project, RecordColourBoardRequest request,
                                           java.util.function.Supplier<PdfAllowanceResponse> billed,
                                           boolean mayClose) {
        // A room off the free library shelf runs this exactly like any other project: the
        // same per-project cap, the same closure when the last board goes, the same
        // monthly download allowance spent through `billed` below. Only the way in was
        // free — the pixels were already stored and no AI ran — and that is a fact about
        // where the photo came from, not about what a finished job is.
        if (project.isClosed()) {
            throw new IllegalStateException(
                    "This project is closed. Reopen it to make another colour board.");
        }
        int allowed = boardsAllowedFor(project);
        if (mayClose && project.getColourBoardsUsed() >= allowed) {
            throw new IllegalStateException(
                    "This project has already handed over all " + allowed
                    + " of its colour board" + (allowed == 1 ? "" : "s") + ".");
        }

        // Refusals first: this throws 402 when the paying plan has no downloads left.
        PdfAllowanceResponse allowance = billed.get();

        // The sheet has to fit the board the payer was quoted. The studio already caps its
        // tray at this number (it reads `imagesPerPdf` off the allowance rather than
        // printing its own), so this only ever fires on a client that ignored it — and it
        // fires AFTER the reservation because the reservation is where the number comes
        // from. Throwing rolls the whole transaction back, the conditional UPDATE above
        // included, so nothing is charged for a board that was refused.
        int pages = request.getPages() == null ? 0 : request.getPages().size();
        if (allowance.getImagesPerPdf() > 0 && pages > allowance.getImagesPerPdf()) {
            throw new IllegalStateException(
                    "A colour board here carries up to " + allowance.getImagesPerPdf()
                    + " colour" + (allowance.getImagesPerPdf() == 1 ? "" : "s")
                    + " — this one has " + pages + ". Remove some and download again.");
        }

        int boardIndex = project.getColourBoardsUsed() + 1;
        recordPages(project, request, boardIndex);

        project.setColourBoardsUsed(boardIndex);
        boolean closed = mayClose && boardIndex >= allowed
                && projectAccessService.close(project);
        projectRepository.save(project);

        if (closed) {
            log.info("Project closed by its last colour board: project={} boards={}",
                    project.getId(), boardIndex);
        }
        return ColourBoardResponse.builder()
                .allowance(allowance)
                .boardsUsed(boardIndex)
                .boardsAllowed(allowed)
                .closed(closed)
                .build();
    }

    private void recordPages(Project project, RecordColourBoardRequest request, int boardIndex) {
        int pageIndex = 0;
        for (RecordColourBoardRequest.Page page : request.getPages()) {
            ProjectPdfPage saved = pageRepository.save(ProjectPdfPage.builder()
                    .project(project)
                    .boardIndex(boardIndex)
                    .pageIndex(pageIndex++)
                    .title(page.getTitle())
                    .build());
            int order = 0;
            for (RecordColourBoardRequest.Shade shade : page.getShades()) {
                saved.getShades().add(ProjectPdfPageShade.builder()
                        .page(saved)
                        .regionId(shade.getRegionId())
                        .regionLabel(shade.getRegionLabel())
                        .shadeCode(shade.getShadeCode())
                        .shadeName(shade.getShadeName())
                        .hexCode(shade.getHex())
                        .displayOrder(order++)
                        .build());
            }
            pageRepository.save(saved);
        }
    }

    /**
     * Close a project because its owner said so, rather than because it ran out of boards.
     *
     * Idempotent, like {@code sendGuestProjectToShop}: pressing the button twice is the
     * same as pressing it once, and the second press is not an error.
     */
    @Transactional
    public void close(Project project) {
        if (projectAccessService.close(project)) {
            projectRepository.save(project);
            log.info("Project closed by its owner: project={} boards={}",
                    project.getId(), project.getColourBoardsUsed());
        }
    }

    /** Every combo this project handed over, in the order the customer saw them. */
    @Transactional(readOnly = true)
    public List<ProjectComboResponse> combos(String projectId) {
        Set<String> renderedPageIds = renderRepository
                .findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(r -> r.getPage() != null)
                .map(r -> r.getPage().getId())
                .collect(Collectors.toSet());
        List<ProjectPdfPage> pages = pageRepository.findByProjectIdWithShades(projectId);
        // One bulk lookup for the whole project rather than a query per swatch. The HV
        // codes ride along so the render page can reprint this board — with the AI image
        // on the end — in the same customer-facing numbering the original carried.
        Map<String, String> hvByCode = shadeDecodeService.hvCodesByShadeCode(
                pages.stream()
                        .flatMap(p -> p.getShades().stream())
                        .map(ProjectPdfPageShade::getShadeCode)
                        .filter(c -> c != null && !c.isBlank())
                        .distinct()
                        .toList());
        return pages.stream()
                .map(page -> ProjectComboResponse.from(
                        page, renderedPageIds.contains(page.getId()), hvByCode))
                .toList();
    }

    /**
     * How many boards THIS project gets, for anyone quoting it before one is spent.
     *
     * <p>A question about the project rather than about the platform, because the two kinds
     * of owner buy different things: a shop works a room over several sheets, a customer
     * bought one job that ends with one. A guest room (no user, an access code instead) takes
     * the shop-side number, which costs it nothing — a guest room never closes on its boards
     * anyway, and its real cap is the allowance its code was sold with.
     */
    public int boardsAllowedFor(Project project) {
        return isCustomerOwned(project) ? customerBoardsPerProject : boardsPerProject;
    }

    private static boolean isCustomerOwned(Project project) {
        User owner = project.getUser();
        return owner != null && owner.getRole() == UserRole.CUSTOMER;
    }

    ProjectPdfPage requirePage(String projectId, String pageId) {
        return pageRepository.findByIdAndProjectId(pageId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That combination isn't on any of this project's colour boards."));
    }
}
