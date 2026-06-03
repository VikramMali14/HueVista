package com.gridstore.huevista.auth.dto;

import com.gridstore.huevista.auth.model.User;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserProfileResponse {
    private String id;
    private String name;
    private String email;
    private String picture;
    private String provider;
    private String role;
    private boolean emailVerified;
    private String phoneNumber;
    private boolean phoneVerified;
    private LocalDateTime createdAt;

    public static UserProfileResponse from(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .picture(user.getPicture())
                .provider(user.getProvider().name())
                .role(user.getRole().name())
                .emailVerified(user.isEmailVerified())
                .phoneNumber(user.getPhoneNumber())
                .phoneVerified(user.isPhoneVerified())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
