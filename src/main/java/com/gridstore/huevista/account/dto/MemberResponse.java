package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.OrgMembership;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemberResponse {
    private Long membershipId;
    private String userId;
    private String name;
    private String email;
    private OrgMemberRole role;
    private LocalDateTime joinedAt;

    public static MemberResponse from(OrgMembership m) {
        return MemberResponse.builder()
                .membershipId(m.getId())
                .userId(m.getUser().getId())
                .name(m.getUser().getName())
                .email(m.getUser().getEmail())
                .role(m.getRole())
                .joinedAt(m.getJoinedAt())
                .build();
    }
}
