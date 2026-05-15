package com.gridstore.huevista.admin;

import com.gridstore.huevista.account.dto.OrgResponse;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AdminUserResponse;
import com.gridstore.huevista.auth.dto.ChangeRoleRequest;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    @Operation(summary = "List all users")
    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> listUsers() {
        return ResponseEntity.ok(
                userRepository.findAll().stream().map(AdminUserResponse::from).toList());
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
            @Valid @RequestBody ChangeRoleRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        user.setRole(request.getRole());
        userRepository.save(user);
        return ResponseEntity.ok(AdminUserResponse.from(user));
    }

    @Operation(summary = "List all organizations")
    @GetMapping("/organizations")
    public ResponseEntity<List<OrgResponse>> listOrganizations() {
        return ResponseEntity.ok(
                orgRepository.findAll().stream().map(OrgResponse::from).toList());
    }

    @Operation(summary = "List all subscriptions")
    @GetMapping("/subscriptions")
    public ResponseEntity<List<SubscriptionResponse>> listSubscriptions() {
        return ResponseEntity.ok(
                subscriptionRepository.findAll().stream().map(SubscriptionResponse::from).toList());
    }

    @Operation(summary = "Platform health summary")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "totalUsers", userRepository.count(),
                "totalOrganizations", orgRepository.count(),
                "totalSubscriptions", subscriptionRepository.count()
        ));
    }
}
