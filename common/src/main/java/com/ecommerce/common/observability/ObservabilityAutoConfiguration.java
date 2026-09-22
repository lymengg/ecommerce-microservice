package com.ecommerce.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires the shared business metrics (Phase 8, ADR-021).
 *
 * <p>Auto-configured rather than component-scanned, for the same reason as the
 * tracing, messaging and resilience configurations: whether a service is
 * observable must not depend on which packages it happens to scan.
 * {@code checkout-service} scans selectively and would otherwise be the one
 * service with no business metrics.
 *
 * <p>Ordered after Boot's {@link MetricsAutoConfiguration} so the
 * {@code MeterRegistry} bean definition exists before the conditional beans
 * below are evaluated — a {@code @ConditionalOnBean} that runs too early
 * silently produces no beans at all.
 *
 * <p>Split into three classes on purpose, and this is not cosmetic. A class-level
 * condition only stops the *class* being processed; once it is processed, Spring
 * resolves every bean-method parameter type, so a method mentioning a type that
 * is absent from the classpath fails with {@code ClassNotFoundException} at
 * startup. This class therefore mentions only Micrometer. The outbox metric lives
 * in {@link OutboxMetricsAutoConfiguration} (needs JPA) and the Kafka metric in
 * {@link KafkaLagMetricsAutoConfiguration} (needs the Kafka client) so that
 * catalog, cart and checkout — which have neither — never load them. Found by
 * running checkout-service, which failed to start with
 * {@code ClassNotFoundException: JpaRepository}.
 */
@AutoConfiguration(after = MetricsAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApplicationMetrics applicationMetrics(MeterRegistry meterRegistry) {
        return new ApplicationMetrics(meterRegistry);
    }
}
