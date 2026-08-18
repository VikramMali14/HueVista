package com.gridstore.huevista.project.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.billing.dto.PdfAllowanceResponse;
import com.gridstore.huevista.billing.service.PdfQuotaService;
import com.gridstore.huevista.paint.service.ShadeDecodeService;
import com.gridstore.huevista.project.dto.ColourBoardResponse;
import com.gridstore.huevista.project.dto.RecordColourBoardRequest;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectPdfPage;
import com.gridstore.huevista.project.repository.ProjectPdfPageRepository;
import com.gridstore.huevista.project.repository.ProjectRenderRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How many colour boards a project hands over, and who decides.
 *
 * The interesting cases are all about the OWNER, because the cap is now two numbers rather
 * than one: a shop works a room over several sheets and a customer bought a job that ends
 * with one board and the render behind it. Everything else here — the page cap, the order
 * of charge-then-record — is the same for both.
 */
class ProjectBoardServiceTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectPdfPageRepository pages = mock(ProjectPdfPageRepository.class);
    private final ProjectRenderRepository renders = mock(ProjectRenderRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);

    private final ProjectBoardService service = new ProjectBoardService(
            projects, pages, renders, access,
            mock(PdfQuotaService.class), mock(ShadeDecodeService.class));

    /**
     * Both caps by hand: the service is built with `new` rather than through Spring, so its
     * {@code @Value} fields would otherwise sit at 0 and close every project on its first
     * board — passing the customer assertions below for entirely the wrong reason.
     */
    {
        ReflectionTestUtils.setField(service, "boardsPerProject", 4);
        ReflectionTestUtils.setField(service, "customerBoardsPerProject", 1);
        when(pages.save(any(ProjectPdfPage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(access.close(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            if (p.isClosed()) return false;
            p.setClosedAt(java.time.LocalDateTime.now());
            return true;
        });
    }

    private static Project projectOwnedBy(UserRole role) {
        User owner = new User();
        owner.setId("owner-1");
        owner.setRole(role);
        return Project.builder().id("project-1").user(owner).build();
    }

    /** A board of {@code n} pages, one shade on each — enough to be recorded. */
    private static RecordColourBoardRequest board(int n) {
        RecordColourBoardRequest request = new RecordColourBoardRequest();
        request.setPages(IntStream.range(0, n).mapToObj(i -> {
            RecordColourBoardRequest.Shade shade = new RecordColourBoardRequest.Shade();
            shade.setRegionLabel("Main wall");
            shade.setHex("#e8d5b0");
            RecordColourBoardRequest.Page page = new RecordColourBoardRequest.Page();
            page.setTitle("Option " + (i + 1));
            page.setShades(List.of(shade));
            return page;
        }).toList());
        return request;
    }

    private static PdfAllowanceResponse allowing(int imagesPerPdf) {
        PdfAllowanceResponse allowance = PdfAllowanceResponse.unmetered();
        allowance.setImagesPerPdf(imagesPerPdf);
        return allowance;
    }

    // ---- the cap depends on who owns the project ----

    @Test
    void a_customers_project_gets_one_board() {
        assertThat(service.boardsAllowedFor(projectOwnedBy(UserRole.CUSTOMER))).isEqualTo(1);
    }

    @Test
    void a_shops_project_keeps_the_platform_number() {
        assertThat(service.boardsAllowedFor(projectOwnedBy(UserRole.RETAILER))).isEqualTo(4);
    }

    /** A guest room has no user at all, and must not be read as a customer's. */
    @Test
    void a_guest_room_takes_the_shop_side_number() {
        assertThat(service.boardsAllowedFor(Project.builder().id("guest-1").build())).isEqualTo(4);
    }

    // ---- one board finishes a customer's job ----

    @Test
    void a_customers_first_board_closes_the_project() {
        Project project = projectOwnedBy(UserRole.CUSTOMER);

        ColourBoardResponse response =
                service.recordBoard(project, board(5), () -> allowing(5));

        assertThat(response.getBoardsUsed()).isEqualTo(1);
        assertThat(response.getBoardsAllowed()).isEqualTo(1);
        assertThat(response.isClosed()).isTrue();
        assertThat(project.isClosed()).isTrue();
    }

    /**
     * The state a project reaches when its cap moved underneath it: boards already spent,
     * but the project still open because it was never closed at the old number. It refuses
     * another board and names the number that applies now, so the studio's own way out —
     * the Close button — is the only thing left to do.
     */
    @Test
    void a_customers_second_board_is_refused() {
        Project project = projectOwnedBy(UserRole.CUSTOMER);
        project.setColourBoardsUsed(1);

        assertThatThrownBy(() -> service.recordBoard(project, board(1), () -> allowing(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all 1 of its colour board");
    }

    @Test
    void a_shops_first_board_leaves_the_project_open() {
        Project project = projectOwnedBy(UserRole.RETAILER);

        ColourBoardResponse response =
                service.recordBoard(project, board(3), () -> allowing(8));

        assertThat(response.getBoardsAllowed()).isEqualTo(4);
        assertThat(response.isClosed()).isFalse();
        assertThat(project.isClosed()).isFalse();
    }

    // ---- the sheet has to fit the board the payer was quoted ----

    @Test
    void a_board_bigger_than_the_allowance_is_refused_and_nothing_is_recorded() {
        Project project = projectOwnedBy(UserRole.CUSTOMER);

        assertThatThrownBy(() -> service.recordBoard(project, board(6), () -> allowing(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("up to 5 colours");

        verify(pages, never()).save(any(ProjectPdfPage.class));
        assertThat(project.getColourBoardsUsed()).isZero();
        assertThat(project.isClosed()).isFalse();
    }

    @Test
    void a_board_exactly_the_size_of_the_allowance_is_fine() {
        Project project = projectOwnedBy(UserRole.CUSTOMER);

        assertThat(service.recordBoard(project, board(5), () -> allowing(5)).getBoardsUsed())
                .isEqualTo(1);
    }

    /** A closed project has nothing left to hand over, whoever owns it. */
    @Test
    void a_closed_project_refuses_before_anything_is_charged() {
        Project project = projectOwnedBy(UserRole.CUSTOMER);
        project.setClosedAt(java.time.LocalDateTime.now());

        assertThatThrownBy(() -> service.recordBoard(project, board(1), () -> {
            throw new AssertionError("the paying plan must not be touched");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }
}
