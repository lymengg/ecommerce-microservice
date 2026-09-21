package com.ecommerce.common.tracing;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers the shared correlation filter for servlet services.
 *
 * <p>Deliberately an auto-configuration rather than a {@code @Component}: a
 * component in this package would only be picked up by services that happen to
 * component-scan it. {@code checkout-service} scans selectively (only
 * {@code com.ecommerce.checkout} plus a couple of {@code common} packages), so a
 * scanned filter would be silently absent there — the same trap that left
 * checkout-service unsecured until Phase 4. Auto-configuration is discovered
 * from {@code META-INF/spring/...AutoConfiguration.imports} regardless of any
 * service's scan configuration.
 *
 * <p>Servlet-only: the gateway is reactive and already has its own
 * {@code GlobalFilter} for this.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TracingAutoConfiguration {

    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
