package com.gridstore.huevista.auth.dto;

import com.gridstore.huevista.auth.model.User;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminUserResponse {
    private String id;
    private String name;
    private String email;
    private String role;
    private String provider;
    private boolean emailVerified;
    private LocalDateTime createdAt;

    public static AdminUserResponse from(User user) {
        return AdminUserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .provider(user.getProvider().name())
                .emailVerified(user.isEmailVerified())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
