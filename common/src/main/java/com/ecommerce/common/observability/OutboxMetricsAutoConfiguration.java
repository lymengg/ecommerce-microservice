package com.ecommerce.common.observability;

import com.ecommerce.common.outbox.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * The outbox backlog metric (Phase 8, ADR-021).
 *
 * <p>Separate from {@link ObservabilityAutoConfiguration} because it needs
 * {@code OutboxRepository}, which extends {@code JpaRepository} — and
 * {@code checkout-service} has no JPA on its classpath at all. A class-level
 * {@code @ConditionalOnClass} is what keeps this class from being loaded there;
 * the guard has to be on this class rather than on the method, because Spring
 * resolves bean-method parameter types as soon as it processes the class.
 *
 * <p>Applies wherever there is an outbox table, including catalog and cart,
 * which have one but never publish (ADR-015). A permanently zero backlog for
 * them is still the truthful answer, and it makes "no events are stuck" a fact
 * rather than an assumption.
 */
@AutoConfiguration(after = MetricsAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaRepository")
public class OutboxMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(OutboxRepository.class)
    public OutboxBacklogMetrics outboxBacklogMetrics(OutboxRepository outboxRepository,
                                                     MeterRegistry meterRegistry) {
        OutboxBacklogMetrics metrics = new OutboxBacklogMetrics(outboxRepository);
        metrics.bindTo(meterRegistry);
        return metrics;
    }
}
