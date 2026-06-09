package com.gridstore.huevista.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        // Use allowedOriginPatterns so wildcards work alongside allowCredentials=true.
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);

        // SECURITY: a bare "*" origin pattern combined with allowCredentials=true makes
        // Spring reflect ANY Origin and echo Access-Control-Allow-Credentials:true — i.e.
        // any website could make credentialed cross-origin calls. Never allow that combo:
        // if a wildcard origin is configured, drop credentials (and warn loudly).
        boolean hasWildcard = origins.stream().anyMatch(o -> o.contains("*"));
        if (hasWildcard) {
            log.warn("CORS allowed-origins contains a wildcard ({}). Disabling allowCredentials "
                    + "to avoid reflecting credentials to arbitrary origins. Configure explicit "
                    + "origins via CORS_ALLOWED_ORIGINS for credentialed requests.", origins);
            config.setAllowCredentials(false);
        } else {
            config.setAllowCredentials(true);
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
