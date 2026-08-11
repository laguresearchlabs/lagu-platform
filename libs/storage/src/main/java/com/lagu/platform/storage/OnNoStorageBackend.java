package com.lagu.platform.storage;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code platform.storage.provider} selects neither backend.
 *
 * <p>The complement of the two {@code @ConditionalOnProperty} conditions on the backend
 * configurations, which cannot express "neither of these" on their own.
 *
 * <p>Deliberately a property check rather than {@code @ConditionalOnMissingBean}: that annotation
 * only has guaranteed ordering inside auto-configuration, and {@code StorageConfig} is a plain
 * {@code @Configuration} imported by component scan. Keying on the same property the backends key
 * on makes the three conditions exhaustive and mutually exclusive by construction, so exactly one
 * {@link StorageService} is registered whatever order Spring processes them in.
 */
public class OnNoStorageBackend implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String provider = context.getEnvironment()
                .getProperty("platform.storage.provider", StorageProperties.DEFAULT_PROVIDER);
        return !StorageProperties.PROVIDER_GCS.equalsIgnoreCase(provider)
                && !StorageProperties.PROVIDER_S3.equalsIgnoreCase(provider);
    }
}
