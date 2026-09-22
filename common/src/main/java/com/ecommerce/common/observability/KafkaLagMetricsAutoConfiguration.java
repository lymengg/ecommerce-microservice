package com.ecommerce.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Kafka consumer lag for this service's own consumer group (Phase 8, ADR-021).
 *
 * <p>Separate from {@link ObservabilityAutoConfiguration} because it needs the
 * Kafka client, which catalog, cart and checkout do not have — see that class's
 * comment for why the guard must be at class level.
 *
 * <p>Off unless {@code ecommerce.metrics.kafka-lag.enabled} is set, and set only
 * in the services that actually consume (order, inventory). A producer has a
 * consumer group id but never commits offsets, so enabling it there would publish
 * a permanently-zero series — worse than absent, because a dashboard cannot tell
 * "no lag" from "not measuring".
 */
@AutoConfiguration(after = MetricsAutoConfiguration.class)
@ConditionalOnClass(name = "org.apache.kafka.clients.admin.AdminClient")
@ConditionalOnProperty(name = "ecommerce.metrics.kafka-lag.enabled", havingValue = "true")
public class KafkaLagMetricsAutoConfiguration {

    @Bean
    public KafkaConsumerLagMetrics kafkaConsumerLagMetrics(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${ecommerce.metrics.kafka-lag.group:${spring.application.name:unknown}}") String group,
            MeterRegistry meterRegistry) {
        KafkaConsumerLagMetrics metrics = new KafkaConsumerLagMetrics(bootstrapServers, group);
        metrics.bindTo(meterRegistry);
        return metrics;
    }
}
