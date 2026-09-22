package com.ecommerce.common.resilience;

import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Binds breaker state, retry counts and bulkhead saturation to Micrometer — the
 * signal doc 08 §4 asks for and Phase 7 deliberately left dormant.
 *
 * <p>Guarded by class-name conditions because {@code resilience4j-micrometer} is
 * optional: the auto-configuration is skipped, not failed, when it is absent.
 *
 * <p>Note there is no {@code @ConditionalOnBean(MeterRegistry.class)} here, and
 * that is not an oversight. A {@code @ConditionalOnBean} only sees bean
 * definitions registered by *earlier* configurations, and the registry is
 * contributed by Boot's own metrics auto-configuration — so the condition was
 * evaluated too early and silently produced no beans at all. The metrics were
 * simply absent, with nothing in the log to say why. Injecting the registry
 * directly sidesteps the ordering question entirely: by the time a bean is
 * instantiated, every definition exists.
 */
@AutoConfiguration(after = ResilienceAutoConfiguration.class)
@ConditionalOnClass(name = {
        "io.micrometer.core.instrument.MeterRegistry",
        "io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics"
})
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
