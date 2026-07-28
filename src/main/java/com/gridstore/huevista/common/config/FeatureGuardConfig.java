package com.gridstore.huevista.common.config;

import com.gridstore.huevista.account.security.FeatureGuardInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the distributor→shop page-grant guard across the API. */
@Configuration
@RequiredArgsConstructor
public class FeatureGuardConfig implements WebMvcConfigurer {

    private final FeatureGuardInterceptor featureGuardInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Scoped to /api/** so static resources, Swagger and the actuator probes never
        // pay for the lookup. The interceptor is a no-op on any handler that carries no
        // @RequiresFeature, which is most of them.
        registry.addInterceptor(featureGuardInterceptor).addPathPatterns("/api/**");
    }
}
