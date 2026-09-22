package com.ecommerce.common.resilience;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the shared resilience policy for every cross-service REST client
 * (Phase 7, ADR-017).
 *
 * <p>Auto-configured rather than component-scanned for the same reason as
 * {@link com.ecommerce.common.tracing.TracingAutoConfiguration} and
 * {@link com.ecommerce.common.messaging.MessagingAutoConfiguration}: whether a
 * service has circuit breakers must not depend on which packages it happens to
 * scan. {@code checkout-service} scans only {@code com.ecommerce.checkout} plus
 * two {@code common} packages, so a scanned factory would be silently absent
 * there — and checkout is the service that most needs the guards.
 */
@AutoConfiguration
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ClientResilienceFactory clientResilienceFactory(ResilienceProperties properties) {
        return new ClientResilienceFactory(properties);
    }
}
