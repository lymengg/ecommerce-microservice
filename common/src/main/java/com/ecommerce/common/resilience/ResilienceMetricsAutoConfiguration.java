package com.ecommerce.common.resilience;

import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Binds breaker state, retry counts and bulkhead saturation to Micrometer.
 *
 * <p>Dormant today: no service has Actuator or a {@code MeterRegistry} yet, so
 * both conditions are false and nothing is created. It exists so that Phase 8's
 * Prometheus wiring surfaces the resilience signals doc 08 §4 asks for without
 * this phase reaching into the metrics stack — the split the roadmap
 * deliberately makes.
 *
 * <p>Guarded by class-name conditions because {@code resilience4j-micrometer}
 * and micrometer are optional: the auto-configuration is skipped, not failed,
 * when they are absent.
 */
@AutoConfiguration(after = ResilienceAutoConfiguration.class)
@ConditionalOnClass(name = {
        "io.micrometer.core.instrument.MeterRegistry",
        "io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics"
})
@ConditionalOnBean(type = "io.micrometer.core.instrument.MeterRegistry")
public class ResilienceMetricsAutoConfiguration {

    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetrics(ClientResilienceFactory factory,
                                                             MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics =
                TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(factory.circuitBreakers());
        metrics.bindTo(meterRegistry);
        return metrics;
    }

    @Bean
    public TaggedRetryMetrics retryMetrics(ClientResilienceFactory factory, MeterRegistry meterRegistry) {
        TaggedRetryMetrics metrics = TaggedRetryMetrics.ofRetryRegistry(factory.retries());
        metrics.bindTo(meterRegistry);
        return metrics;
    }

    @Bean
    public TaggedBulkheadMetrics bulkheadMetrics(ClientResilienceFactory factory, MeterRegistry meterRegistry) {
        TaggedBulkheadMetrics metrics = TaggedBulkheadMetrics.ofBulkheadRegistry(factory.bulkheads());
        metrics.bindTo(meterRegistry);
        return metrics;
    }
}
