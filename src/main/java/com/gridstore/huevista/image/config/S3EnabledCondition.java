package com.gridstore.huevista.image.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Activates S3 beans only when app.s3.bucket-name resolves to a non-blank value.
 * Using a custom Condition instead of @ConditionalOnProperty because Spring Framework 7
 * throws PlaceholderResolutionException when the property value contains an unset env-var
 * placeholder (e.g. ${S3_BUCKET_NAME} with no default and S3_BUCKET_NAME not exported).
 */
public class S3EnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        try {
            String value = context.getEnvironment().getProperty("app.s3.bucket-name");
            return value != null && !value.isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}
