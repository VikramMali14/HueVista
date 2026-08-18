package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.dto.CreateRenderRequest;
import com.gridstore.huevista.project.dto.ProjectRenderResponse;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectPdfPage;
import com.gridstore.huevista.project.model.ProjectRender;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.repository.ProjectRenderRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The last step of a project: one photorealistic AI render of a combination the customer
 * already chose and was handed on paper.
 *
 * Three things make this different from every other AI call in the product, and all three
 * are deliberate.
 *
 * <p><b>It is made from a combination, and nothing else gates it.</b> A render shows a
 * scheme the customer committed to on paper, which is what makes it trustworthy — not a
 * forty-first idea invented afterwards. That is enforced by requiring a page off one of
 * this project's colour boards. It is NOT enforced by requiring the project to be closed
 * any more: an AI image is paid for with an AI credit, and a customer holding one they
 * bought should be able to spend it whenever they like, on a room in any state.
 *
 * <p><b>It fails LOUD.</b> {@link ImageCleanerService} falls back to the original photo
 * when the model refuses, because a cleaned photo is an improvement on a photo and its
 * absence is survivable. A render has no fallback: the generated image IS the deliverable,
 * so a failure has to be reported, and the allowance handed back.
 *
 * <p><b>It is bought, always, with an AI credit.</b> There used to be a second pocket: a
 * project the account paid for itself carried one INCLUDED image, while a room a shop
 * handed over carried none. Which pocket paid depended on how the room had been bought,
 * three payment routes back — a rule nobody could predict from the outside, and one that
 * made "an AI image costs a credit" false for exactly the rooms people asked about most.
 * There is one pocket now, it belongs to the ACCOUNT rather than to the room, and a credit
 * works on any room the account owns.
 *
 * <p><b>The credit is spent up front and refunded on failure.</b> Charging first is what
 * stops two browser tabs each starting an image on the same credit. It also means the
 * refund path is real code that runs, rather than the "there is no refund endpoint
 * anywhere in the billing module" the colour-board download has to live with.
 *
 * <p>This class owns every database moment; {@link ProjectRenderWorker} owns the minute of
 * HTTP in between. They are separate beans because Spring's {@code @Async} and
 * {@code @Transactional} are proxy-based: a method calling its own annotated method goes
 * straight down the class and picks up neither, so a worker living here would have run on
 * the request thread with no transaction at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectRenderService {

    private final ProjectRepository projectRepository;
    private final ProjectRenderRepository renderRepository;
    private final com.gridstore.huevista.project.repository.ProjectPdfPageRepository pageRepository;
    private final ProjectBoardService boardService;
    private final RenderPromptBuilder promptBuilder;
    private final StorageService storageService;
    private final ProjectRenderWorker worker;
    private final com.gridstore.huevista.billing.service.AiCreditService aiCreditService;
    private final com.gridstore.huevista.billing.service.PricingService pricingService;
    private final com.gridstore.huevista.paint.service.ShadeDecodeService shadeDecodeService;

    /**
     * Everything the model call needs, read out of the database in one go and carried to
     * the worker as plain values.
     *
     * The entities are deliberately left behind. The worker runs on the AI executor, where
     * there is no session, so a lazy field touched out there throws
     * {@code LazyInitializationException} — and holding a transaction open across a minute
     * of polling to avoid that would be worse than the bug.
     */
    public record RenderJob(String renderId, String prompt, List<String> imageUrls,
                            String ownerFolder, ProjectRender.Quality quality) {}

    /**
     * Accept a render request: check it may be made, pay for it, and hand the work to the
     * AI executor.
     *
     * Payment happens inside this transaction, before anything asynchronous starts. Doing
     * it the other way round — start the work, charge when it lands — is what lets a
     * customer with one included render open two tabs and get two.
     */
    @Transactional
    public ProjectRenderResponse request(Project project, CreateRenderRequest request) {
        // No closure gate. Making the picture used to require finishing the job first,
        // which read as a second lock on top of the one that already governs it: an AI
        // image costs an AI credit, and a customer holding a credit they paid for should
        // never be told the room is in the wrong state to spend it. The combination is
        // still required — there is nothing to photograph without colours — but that is
        // the SUBJECT of the render, not a gate in front of it, and a project that has
        // handed over a board has combinations whether it went on to close or not.
        ProjectPdfPage page = boardService.requirePage(project.getId(), request.getComboId());
        // Null reads as BASIC: a client that names no quality is asking for the ordinary
        // picture, and defaulting the other way would charge four credits for silence.
        ProjectRender.Quality quality = request.getQuality() == null
                ? ProjectRender.Quality.BASIC
                : request.getQuality();
        // Same rule, same reason: silence means the picture this made before the choice
        // existed, which is the cleaned one.
        ProjectRender.SourceImage sourceImage = request.getSourceImage() == null
                ? ProjectRender.SourceImage.CLEANED
                : request.getSourceImage();
        Funding funding = charge(project, quality);

        ProjectRender render = renderRepository.save(ProjectRender.builder()
                .project(project)
                .page(page)
                .status(ProjectRender.Status.QUEUED)
                .timeOfDay(request.getTimeOfDay())
                .borderMode(request.getBorderMode())
                .lighting(request.getLighting())
                .furnishing(request.getFurnishing())
                .style(request.getStyle())
                .quality(quality)
                .sourceImage(sourceImage)
                .note(request.getNote())
                // Always true now that the allowance is gone. Kept on the row because
                // renders written before this change record which pocket paid for them,
                // and a column that answers "was this a credit?" with "sometimes, before
                // August" is only readable if it keeps being written.
                .paidWithCredit(true)
                .paidByUserId(funding.walletUserId())
                .creditsSpent(funding.credits())
                .build());

        log.info("Render requested: project={} render={} combo={} quality={} source={} used={} paidWith={}",
                project.getId(), render.getId(), page.getId(), quality, sourceImage,
                project.getRendersUsed(), funding.describe());

        dispatchOnceCommitted(render.getId());
        return ProjectRenderResponse.from(render, null);
    }

    /**
     * How one render was paid for, so the failure path can hand back the same thing.
     *
     * <p>One shape now, where there were three. The other two described a project's
     * included image and the case where a better tier topped that image up out of the
     * wallet — both gone with the allowance itself, and with them the only reason this
     * needed to be a record of two independent quantities rather than a number.
     */
    private record Funding(String walletUserId, int credits) {
        String describe() {
            return credits + " credit(s)";
        }
    }

    /**
     * Take payment for one render, out of the owner's AI credit wallet.
     *
     * <p>The wallet is the only route. It used to be the fallback behind a per-project
     * allowance, and the allowance came first — which meant the answer to "what does an AI
     * image cost?" was "it depends how this room was bought", and on some rooms it was
     * nothing. That included image is gone, so this is one question with one answer.
     *
     * <p>A room with no account behind it — a walk-in guest's, which has no wallet to spend
     * from — is refused rather than run free. It is the same refusal as before; only the
     * sentence changed, because there is no longer an included image for it to be about.
     */
    private Funding charge(Project project, ProjectRender.Quality quality) {
        int cost = pricingService.aiCreditRenderCost(quality);
        String walletUserId = project.getUser() != null ? project.getUser().getId() : null;

        if (walletUserId == null) {
            throw new QuotaExceededException(
                    "An AI image is made with AI image credits. Sign in with your own account "
                    + "to buy them and make one of this room.");
        }

        // Throws 402 with the balance in the message when the wallet is short, which is the
        // studio's "you need to pay for this" branch.
        aiCreditService.spend(walletUserId, cost, project.getId(),
                "1 " + quality.name().toLowerCase(java.util.Locale.ROOT)
                + " AI image · " + project.getName());
        // Counted only once the credit is actually gone, so a refused charge never moves it.
        project.setRendersUsed(project.getRendersUsed() + 1);
        projectRepository.save(project);
        return new Funding(walletUserId, cost);
    }

    /**
     * Hand the render to the AI executor — but only once this transaction has committed.
     *
     * Handing it over inline reads as equivalent and is not. The worker opens its own
     * transaction on another thread, and until this one commits the row it was just given
     * the id of does not exist to anybody else. The worker therefore lost that race
     * essentially every time: it logged "Render vanished before it ran" and stopped, which
     * left the render QUEUED for ever with the allowance already spent and no failure to
     * trigger the refund — the project's one render gone, and a spinner that never stops.
     *
     * <p>It is worth being precise about why this was not merely flaky. {@code request} is
     * called from {@code ProjectService.requestRender}, which is itself
     * {@code @Transactional}, so the commit is two proxies further out than the dispatch —
     * the worker had a comfortable head start on a row that had not been written yet.
     *
     * <p>Committing first also fixes a quieter version of the same mistake. The executor
     * runs {@code CallerRunsPolicy}, so a saturated pool makes the submitting thread run
     * the task itself — which inline meant a minute of model HTTP inside an open database
     * transaction. After commit, the worst that costs is a minute on the request thread.
     *
     * <p>The no-transaction branch is for direct callers in tests, where registering a
     * synchronization would throw rather than run.
     */
    private void dispatchOnceCommitted(String renderId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.run(renderId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.run(renderId);
            }
        });
    }

    /**
     * Read everything the model needs while a session is still open, and mark the render
     * RUNNING on the way past.
     *
     * Returns empty when the render has vanished under us — a project deleted between the
     * request and the worker picking it up — which is a reason to stop, not to fail.
     *
     * <p>Also returns empty when the render is no longer QUEUED. That is the handshake with
     * {@link ProjectRenderSweeper}: a render it has already given up on and refunded must
     * not then quietly run and charge for itself, and a render already RUNNING must not be
     * started twice.
     */
    @Transactional
    public java.util.Optional<RenderJob> startJob(String renderId) {
        ProjectRender render = renderRepository.findById(renderId).orElse(null);
        if (render == null) {
            log.warn("Render vanished before it ran: render={}", renderId);
            return java.util.Optional.empty();
        }
        if (render.getStatus() != ProjectRender.Status.QUEUED) {
            log.warn("Render is not queued any more, leaving it alone: render={} status={}",
                    renderId, render.getStatus());
            return java.util.Optional.empty();
        }
        Project project = render.getProject();
        ImageType imageType = project.getImage().getImageType();

        List<String> images = new ArrayList<>();
        images.add(storageService.getPublicUrl(sourceImageKey(project, render.getSourceImage())));
        if (render.getBorderMode() == ProjectRender.BorderMode.KEEP_ORIGINAL) {
            images.addAll(maskUrls(project));
        }

        render.setStatus(ProjectRender.Status.RUNNING);
        renderRepository.save(render);

        return java.util.Optional.of(new RenderJob(
                renderId,
                promptBuilder.build(render, render.getPage(), imageType),
                images,
                ownerFolder(project),
                // Which tier was paid for, carried out to the worker: it decides which
                // model chain is asked and at what size. Read here rather than out there
                // because out there is another thread with no session to read it in.
                render.getQuality() == null ? ProjectRender.Quality.BASIC : render.getQuality()));
    }

    /**
     * Which photograph the model is given, and the one case where the answer is not the
     * one that was asked for.
     *
     * <p>CLEANED is what almost every render wants — clutter gone, every paintable surface
     * flattened to white, so the model tints a neutral wall instead of fighting the colour
     * already on it. ORIGINAL is a real choice and not a fallback: the clean-up is itself
     * an AI step, and it sometimes takes a picture rail or a texture with it that was the
     * point of the photograph.
     *
     * <p>A room with no cleaned photo gets its original whichever was asked for. That is
     * the substitution this code has always made silently; it is unchanged, and it is safe
     * to make silently because the two answers coincide — there is only one picture of that
     * room, and it is the one being returned.
     */
    private static String sourceImageKey(Project project, ProjectRender.SourceImage choice) {
        String cleaned = project.getCleanedImageStorageKey();
        if (choice == ProjectRender.SourceImage.ORIGINAL || cleaned == null) {
            return project.getImage().getStorageKey();
        }
        return cleaned;
    }

    /**
     * The region masks, so "keep the original borders" means the boundaries this project
     * actually has rather than the model's idea of them.
     *
     * A region carries whichever mask it has — hand-drawn if the customer corrected that
     * wall, generated otherwise — so there is nothing to choose between here; they go in
     * the order the studio shows them. Regions with no mask at all are skipped rather
     * than faked.
     */
    private List<String> maskUrls(Project project) {
        return project.getRegions().stream()
                .sorted(java.util.Comparator.comparingInt(Region::getDisplayOrder))
                .map(Region::getMaskUrl)
                .filter(key -> key != null && !key.isBlank())
                .map(storageService::getPublicUrl)
                .toList();
    }

    /** Where a render's bytes live: the owner's folder, or the code's for a guest room. */
    private static String ownerFolder(Project project) {
        if (project.getUser() != null) return project.getUser().getId();
        if (project.getAccessCode() != null) return project.getAccessCode().getId();
        return "orphaned";
    }

    // ── Finishing, each in its own transaction ──────────────────────────────
    //
    // REQUIRES_NEW because these are called from the worker thread, where there is no
    // caller transaction to join and a rollback would leave a render stuck RUNNING with
    // its allowance spent.

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(String renderId, String storageKey) {
        renderRepository.findById(renderId).ifPresent(r -> {
            r.setStatus(ProjectRender.Status.READY);
            r.setStorageKey(storageKey);
            r.setCompletedAt(LocalDateTime.now());
            renderRepository.save(r);
            log.info("Render ready: render={}", renderId);
        });
    }

    /**
     * Record the failure and hand back whatever paid for it.
     *
     * The refund is the important half. A customer whose image the model could not produce
     * has to be able to try again without paying twice.
     *
     * <p>How much goes back is read off the RENDER — {@code creditsSpent}, recorded when
     * the charge was taken — never recomputed from today's prices or guessed from the
     * project. A tier re-priced between the request and the failure would otherwise refund
     * the wrong number, and the old two-pocket version of this had a worse version of the
     * same bug: it guessed which pocket had paid from the project's state, and quietly kept
     * a customer's credit on any room whose allowance was zero.
     *
     * <p>The count of images this room has made comes back too, for every failure and not
     * just a credit-paid one. It is a count of pictures that exist, and this one does not.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String renderId, String reason) {
        renderRepository.findById(renderId).ifPresent(r -> {
            r.setStatus(ProjectRender.Status.FAILED);
            r.setFailureReason(reason);
            r.setCompletedAt(LocalDateTime.now());
            renderRepository.save(r);

            // By ID, and re-read here. `r.getProject()` is a lazy proxy that may have been
            // created in the session that WROTE the render — this method runs REQUIRES_NEW
            // on the worker thread, so that session is long gone and touching a field on
            // the proxy throws LazyInitializationException rather than loading anything.
            // The id is the one thing a proxy always answers without initialising.
            String projectId = r.getProject().getId();
            if (r.getCreditsSpent() > 0) {
                aiCreditService.refundRender(r.getPaidByUserId(), r.getCreditsSpent(), projectId,
                        // A fresh window on the way back, at the rate this buyer buys at:
                        // a year for a customer, no expiry at all for a shop.
                        pricingService.aiCreditValidityDays(r.getPaidByUserId()));
                log.info("Render credits returned after a failure: project={} render={} credits={}",
                        projectId, renderId, r.getCreditsSpent());
            }
            projectRepository.findById(projectId).ifPresent(project -> {
                if (project.getRendersUsed() > 0) {
                    project.setRendersUsed(project.getRendersUsed() - 1);
                    projectRepository.save(project);
                }
            });
        });
    }

    /**
     * The renders that can no longer finish: still QUEUED or RUNNING long after any real
     * one would have ended.
     *
     * A render is bounded from the moment it starts — the model is polled ninety times at
     * two seconds, so about three minutes and then it gives up. Anything still not terminal
     * an order of magnitude past that is not slow, it is stranded: the process died holding
     * it, or it was accepted and never picked up. Both leave the allowance spent, which is
     * the reason this query exists rather than a dashboard someone has to read.
     *
     * <p>Ids rather than entities, because the caller finishes each one in its own
     * transaction and a detached entity would be worth nothing there.
     */
    @Transactional(readOnly = true)
    public List<String> strandedRenderIds(Duration olderThan) {
        return renderRepository.findByStatusInAndCreatedAtBefore(
                        List.of(ProjectRender.Status.QUEUED, ProjectRender.Status.RUNNING),
                        LocalDateTime.now().minus(olderThan))
                .stream()
                .map(ProjectRender::getId)
                .toList();
    }

    /** Store the finished bytes and return the storage KEY — never a presigned URL. */
    public String store(byte[] image, String ownerFolder) throws java.io.IOException {
        return storageService.store(image, ownerFolder, "render.jpg", "image/jpeg");
    }

    // ── Reading ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProjectRenderResponse> list(String projectId) {
        return renderRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(r -> ProjectRenderResponse.from(r, urlFor(r)))
                .toList();
    }

    /**
     * Every finished image this account owns, newest first, across all of its rooms.
     *
     * The per-project {@link #list} answers the studio, which already has one project
     * open. This answers the customer who made an image last week and wants to find it
     * again — until now the only route back was remembering which room it was on and
     * reopening that room's render page, so an image somebody paid ₹99 for was, in
     * practice, wherever their downloads folder had put it.
     *
     * <p>One HV-code lookup for the whole page rather than one per swatch, the same bulk
     * call {@link ProjectBoardService#combos} makes: the codes are needed so a sheet
     * printed from here carries the shop's own customer-facing numbering, and asking the
     * catalogue once for thirty images beats asking it ninety times.
     */
    @Transactional(readOnly = true)
    public List<com.gridstore.huevista.project.dto.MyRenderResponse> listForOwner(String userId) {
        return describe(renderRepository.findByOwnerAndStatus(userId, ProjectRender.Status.READY));
    }

    /**
     * The rooms this account can start a NEW image from: its closed projects that handed
     * over a colour board.
     *
     * <p>Why this exists at all: an image could only ever be asked for from inside the room
     * that made it. Somebody who wanted another picture of a job they finished last month
     * had to remember which room it was, find it in a dashboard of finished work, open it,
     * and only then be offered the choice — for a purchase that no longer depends on the
     * room's state in any way, since a credit is a credit. The AI-images page is where
     * "another one, please" is actually said, so the picking happens there.
     *
     * <p>Two queries, not one per room. The projects come back with their photographs
     * fetch-joined, and the combination counts arrive in a single grouped count — a shelf
     * of finished work is exactly the size at which an N+1 stops being invisible.
     *
     * <p>Both image URLs are presigned here rather than left as storage keys, because a key
     * is worth nothing to a browser and the picker's whole job is to show two pictures side
     * by side and ask which one to paint.
     */
    @Transactional(readOnly = true)
    public List<com.gridstore.huevista.project.dto.RenderableProjectResponse> renderableProjects(
            String userId) {
        List<Project> projects = projectRepository.findClosedWithCombos(userId);
        if (projects.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, Long> comboCounts = pageRepository
                .countByProjectIds(projects.stream().map(Project::getId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row[0], row -> (Long) row[1]));
        return projects.stream()
                .map(p -> com.gridstore.huevista.project.dto.RenderableProjectResponse.builder()
                        .id(p.getId())
                        .name(p.getName())
                        .roomType(p.getRoomType())
                        .imageUrl(storageService.getPublicUrl(p.getImage().getStorageKey()))
                        .cleanedImageUrl(p.getCleanedImageStorageKey() == null ? null
                                : storageService.getPublicUrl(p.getCleanedImageStorageKey()))
                        .closedAt(p.getClosedAt())
                        .comboCount(comboCounts.getOrDefault(p.getId(), 0L).intValue())
                        .build())
                .toList();
    }

    /**
     * The same shelf, read by the SHOP that issued the code the rooms were made against.
     *
     * <p>A shop pays for the room, prints the colour board and takes the order — and then
     * had no way to see the one thing the customer actually leaves with. The picture was
     * visible to the account that made it and to nobody else, so a customer ringing the
     * counter about "the image you did for my hall" was describing something the shop
     * could not open.
     *
     * <p>Scoped to the code rather than to the customer on purpose — see
     * {@link ProjectRenderRepository#findByAccessCodeAndStatus}. Read-only in the strongest
     * sense: there is no shop-facing route that creates, re-runs or deletes one of these,
     * because the credit that paid for it was the customer's.
     *
     * <p>The caller checks that the code is this shop's before calling. This method takes
     * an id from the request and does not verify it.
     */
    @Transactional(readOnly = true)
    public List<com.gridstore.huevista.project.dto.MyRenderResponse> listForAccessCode(String accessCodeId) {
        return describe(
                renderRepository.findByAccessCodeAndStatus(accessCodeId, ProjectRender.Status.READY));
    }

    /**
     * Renders → responses, with one HV-code lookup for the whole page rather than one per
     * swatch — the same bulk call {@link ProjectBoardService#combos} makes. The codes are
     * needed so a sheet printed from either shelf carries the shop's own customer-facing
     * numbering, and asking the catalogue once for thirty images beats asking it ninety
     * times.
     */
    private List<com.gridstore.huevista.project.dto.MyRenderResponse> describe(List<ProjectRender> renders) {
        if (renders.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, String> hvByCode = shadeDecodeService.hvCodesByShadeCode(
                renders.stream()
                        .map(ProjectRender::getPage)
                        .filter(java.util.Objects::nonNull)
                        .flatMap(p -> p.getShades().stream())
                        .map(com.gridstore.huevista.project.model.ProjectPdfPageShade::getShadeCode)
                        .filter(c -> c != null && !c.isBlank())
                        .distinct()
                        .toList());
        return renders.stream()
                .map(r -> com.gridstore.huevista.project.dto.MyRenderResponse.from(r, urlFor(r), hvByCode))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectRenderResponse get(String projectId, String renderId) {
        ProjectRender render = renderRepository.findByIdAndProjectId(renderId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Render not found: " + renderId));
        return ProjectRenderResponse.from(render, urlFor(render));
    }

    /** Presigned fresh on every read — the row holds a key, never a URL. */
    private String urlFor(ProjectRender render) {
        return render.getStorageKey() == null ? null
                : storageService.getPublicUrl(render.getStorageKey());
    }
}
