package com.gridstore.huevista.painter.dto;

import com.gridstore.huevista.painter.model.PainterProfile;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PainterProfileResponse {
    private String userId;
    private String name;
    private String email;
    private String phone;
    private List<String> serviceAreas;
    private List<String> specialties;
    private Integer yearsExperience;
    private BigDecimal dayRateInr;
    private BigDecimal rating;
    private Integer jobsCompleted;
    private boolean active;
    private LocalDateTime createdAt;

    public static PainterProfileResponse from(PainterProfile p) {
        return PainterProfileResponse.builder()
                .userId(p.getUserId())
                .name(p.getUser().getName())
                .email(p.getUser().getEmail())
                .phone(p.getPhone())
                .serviceAreas(p.getServiceAreas())
                .specialties(p.getSpecialties())
                .yearsExperience(p.getYearsExperience())
                .dayRateInr(p.getDayRateInr())
                .rating(p.getRating())
                .jobsCompleted(p.getJobsCompleted())
                .active(p.isActive())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
