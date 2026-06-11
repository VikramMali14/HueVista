package com.gridstore.huevista.admin;

import com.gridstore.huevista.account.dto.OrgResponse;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AdminUserResponse;
import com.gridstore.huevista.auth.dto.ChangeRoleRequest;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.repository.ProjectRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gridstore.huevista.common.audit.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Super-admin endpoints — ROLE_ADMIN only")
public class AdminController {

    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ProjectRepository projectRepository;
    private final AuditService auditService;

    /** Hard cap on admin page sizes — these tables are unbounded. */
    private static final int MAX_PAGE_SIZE = 500;

    /** Clamps page/size (page >= 0, 1 <= size <= 500); stable newest-first ordering. */
    private static Pageable pageOf(int page, int size) {
        return PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
    }

    @Operation(summary = "List all users")
    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> listUsers(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 500") @RequestParam(defaultValue = "200") int size) {
        return ResponseEntity.ok(
                userRepository.findAll(pageOf(page, size)).getContent()
                        .stream().map(AdminUserResponse::from).toList());
    }

    @Operation(summary = "Get user by ID")
    @GetMapping("/users/{userId}")
    public ResponseEntity<AdminUserResponse> getUser(@PathVariable String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        return ResponseEntity.ok(AdminUserResponse.from(user));
    }

    @Operation(summary = "Change user role", description = "Promotes or demotes a user's system role.")
    @PatchMapping("/users/{userId}/role")
    public ResponseEntity<AdminUserResponse> changeRole(
            @PathVariable String userId,
            @Valid @RequestBody ChangeRoleRequest request,
            Authentication auth) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        var previous = user.getRole();
        user.setRole(request.getRole());
        userRepository.save(user);
        auditService.record(auth.getName(), "ROLE_CHANGE", "USER", userId,
                previous + " -> " + request.getRole());
        return ResponseEntity.ok(AdminUserResponse.from(user));
    }

    @Operation(summary = "List all organizations")
    @GetMapping("/organizations")
    public ResponseEntity<List<OrgResponse>> listOrganizations(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 500") @RequestParam(defaultValue = "200") int size) {
        return ResponseEntity.ok(
                orgRepository.findAll(pageOf(page, size)).getContent()
                        .stream().map(OrgResponse::from).toList());
    }

    @Operation(summary = "List all subscriptions")
    @GetMapping("/subscriptions")
    public ResponseEntity<List<SubscriptionResponse>> listSubscriptions(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 500") @RequestParam(defaultValue = "200") int size) {
        return ResponseEntity.ok(
                subscriptionRepository.findAll(pageOf(page, size)).getContent()
                        .stream().map(SubscriptionResponse::from).toList());
    }

    @Operation(summary = "Platform health summary")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        long activeSubscriptions = subscriptionRepository.countByStatus(SubscriptionStatus.ACTIVE);
        long totalProjects = projectRepository.count();
        long segmentedProjects = projectRepository.countByStatus(ProjectStatus.SEGMENTED);
        long failedProjects = projectRepository.countByStatus(ProjectStatus.FAILED);
        long newUsersLast30Days = userRepository.countByCreatedAtAfter(LocalDateTime.now().minusDays(30));
        long totalAiGenerations = subscriptionRepository.sumAiGenerationsUsedByStatus(SubscriptionStatus.ACTIVE);

        return ResponseEntity.ok(Map.of(
                "totalUsers", userRepository.count(),
                "newUsersLast30Days", newUsersLast30Days,
                "totalOrganizations", orgRepository.count(),
                "totalSubscriptions", subscriptionRepository.count(),
                "activeSubscriptions", activeSubscriptions,
                "totalProjects", totalProjects,
                "segmentedProjects", segmentedProjects,
                "failedProjects", failedProjects,
                "totalAiGenerationsUsed", totalAiGenerations
        ));
    }

    @Operation(summary = "Recent signups (last 10 users)")
    @GetMapping("/users/recent")
    public ResponseEntity<List<AdminUserResponse>> recentUsers() {
        return ResponseEntity.ok(
                userRepository.findTop10ByOrderByCreatedAtDesc()
                        .stream().map(AdminUserResponse::from).toList());
    }

    @Operation(summary = "Subscription revenue breakdown by plan")
    @GetMapping("/stats/revenue")
    public ResponseEntity<Map<String, Object>> revenueStats() {
        List<Object[]> rows = subscriptionRepository.countByPlanAndStatus(SubscriptionStatus.ACTIVE);
        Map<String, Long> countByPlan = new LinkedHashMap<>();
        Map<String, Double> revenueByPlan = new LinkedHashMap<>();
        long totalMonthlyRevenue = 0;
        for (Object[] row : rows) {
            Plan plan = (Plan) row[0];
            long count = (Long) row[1];
            countByPlan.put(plan.getDisplayName(), count);
            double revenue = plan.getPriceInPaise() > 0 ? (plan.getPriceInPaise() / 100.0) * count : 0;
            revenueByPlan.put(plan.getDisplayName(), revenue);
            totalMonthlyRevenue += plan.getPriceInPaise() > 0 ? plan.getPriceInPaise() * count : 0;
        }
        return ResponseEntity.ok(Map.of(
                "activeSubscriptionsByPlan", countByPlan,
                "monthlyRevenueByPlanInRupees", revenueByPlan,
                "totalEstimatedMonthlyRevenueInRupees", totalMonthlyRevenue / 100.0
        ));
    }

    @Operation(summary = "AI usage statistics")
    @GetMapping("/stats/ai-usage")
    public ResponseEntity<Map<String, Object>> aiUsageStats() {
        long totalUsed = subscriptionRepository.sumAiGenerationsUsedByStatus(SubscriptionStatus.ACTIVE);
        long activeCount = subscriptionRepository.countByStatus(SubscriptionStatus.ACTIVE);
        double avgPerActiveUser = activeCount > 0 ? (double) totalUsed / activeCount : 0;
        return ResponseEntity.ok(Map.of(
                "totalAiGenerationsUsedThisCycle", totalUsed,
                "activeSubscriptions", activeCount,
                "avgAiGenerationsPerActiveSubscription", Math.round(avgPerActiveUser * 100.0) / 100.0
        ));
    }
}
