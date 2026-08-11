package com.gridstore.huevista.project.service;

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

    @Value("${app.project.colour-boards-per-project:2}")
    private int boardsPerProject;

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
        if (project.isClosed()) {
            throw new IllegalStateException(
                    "This project is closed. Reopen it to make another colour board.");
        }
        if (project.getColourBoardsUsed() >= boardsPerProject) {
            throw new IllegalStateException(
                    "This project has already handed over all " + boardsPerProject
                    + " of its colour boards.");
        }

        // Refusals first: this throws 402 when the paying plan has no downloads left.
        PdfAllowanceResponse allowance = billed.get();

        int boardIndex = project.getColourBoardsUsed() + 1;
        recordPages(project, request, boardIndex);

        project.setColourBoardsUsed(boardIndex);
        boolean closed = boardIndex >= boardsPerProject && projectAccessService.close(project);
        projectRepository.save(project);

        if (closed) {
            log.info("Project closed by its last colour board: project={} boards={}",
                    project.getId(), boardIndex);
        }
        return ColourBoardResponse.builder()
                .allowance(allowance)
                .boardsUsed(boardIndex)
                .boardsAllowed(boardsPerProject)
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
        return pageRepository.findByProjectIdWithShades(projectId).stream()
                .map(page -> ProjectComboResponse.from(page, renderedPageIds.contains(page.getId())))
                .toList();
    }

    /** How many boards a project gets, for anyone quoting it before one is spent. */
    public int boardsPerProject() {
        return boardsPerProject;
    }

    ProjectPdfPage requirePage(String projectId, String pageId) {
        return pageRepository.findByIdAndProjectId(pageId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That combination isn't on any of this project's colour boards."));
    }
}
