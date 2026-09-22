package com.ecommerce.common.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka consumer lag for this service's own consumer group (Phase 8, ADR-021) —
 * the other metric docs/13 singles out.
 *
 * <p>Why not the client-side lag Micrometer already exposes for free: that is
 * {@code kafka.consumer.fetch.manager.records.lag.max}, an estimate from records
 * *this* poll fetched. It reads zero whenever the consumer is not fetching —
 * which includes the case you most need to see, a consumer that has stopped.
 * This measures the real thing: how far the group's committed offsets are behind
 * the end of each partition.
 *
 * <p>Why in the application rather than from an external exporter: the service
 * is already a Kafka client on the correct advertised listener, so it can ask
 * the broker directly with no extra infrastructure. The tidier option — the
 * collector's {@code kafkametrics} receiver — needs the broker to advertise a
 * second, container-internal listener, because the advertised listener is
 * {@code localhost:9092} for host-run clients. That belongs with Phase 9, when
 * the services themselves move into containers; the trade-off is recorded in
 * ADR-021.
 *
 * <p>Refresh runs on the scheduler, not at scrape time: it is a network round
 * trip to the broker, and a scrape must not wait on Kafka. Gauges therefore read
 * the last refreshed values.
 */
public class KafkaConsumerLagMetrics implements MeterBinder, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerLagMetrics.class);

    private final String group;
    private final Admin admin;
    private final Map<String, AtomicLong> lagByTopic = new ConcurrentHashMap<>();
    private final AtomicLong totalLag = new AtomicLong();
    private MeterRegistry registry;

    public KafkaConsumerLagMetrics(String bootstrapServers, String group) {
        this.group = group;
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "consumer-lag-metrics-" + group);
        // Bounded on purpose: this runs inside the application on a schedule, and
        // the client default is a 60 s API timeout. An unreachable broker must
        // not tie up a scheduler thread for a minute at a time.
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        this.admin = AdminClient.create(properties);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        // Named `.aggregate`, not `.total`: Micrometer's Prometheus convention
        // strips a trailing `.total` from a non-counter, so the series would have
        // appeared as plain `kafka_consumer_lag` — indistinguishable from the
        // per-topic gauges below and a double-counting trap for any `sum()`.
        Gauge.builder("kafka.consumer.lag.aggregate", totalLag, AtomicLong::get)
                .description("Total committed-offset lag across all assigned partitions")
                .tag("group", group)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${ecommerce.metrics.kafka-lag.interval-ms:15000}",
            initialDelayString = "${ecommerce.metrics.kafka-lag.initial-delay-ms:20000}")
    public void refresh() {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS);
            if (committed.isEmpty()) {
                totalLag.set(0);
                return;
            }
            Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
            committed.keySet().forEach(partition -> latestSpecs.put(partition, OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(latestSpecs).all().get(5, TimeUnit.SECONDS);

            Map<String, Long> perTopic = new HashMap<>();
            committed.forEach((partition, offset) -> {
                ListOffsetsResult.ListOffsetsResultInfo end = ends.get(partition);
                if (end == null) {
                    return;
                }
                long lag = Math.max(0, end.offset() - offset.offset());
                perTopic.merge(partition.topic(), lag, Long::sum);
            });

            perTopic.forEach((topic, lag) -> gaugeFor(topic).set(lag));
            totalLag.set(perTopic.values().stream().mapToLong(Long::longValue).sum());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            // An unknown group (nothing consumed yet) and an unreachable broker
            // both land here. Neither is worth failing a scheduled task over;
            // the broker's own availability is visible elsewhere.
            log.debug("Could not refresh Kafka consumer lag for group {}: {}", group, ex.toString());
        }
    }

    private AtomicLong gaugeFor(String topic) {
        return lagByTopic.computeIfAbsent(topic, t -> {
            AtomicLong holder = new AtomicLong();
            if (registry != null) {
                Gauge.builder("kafka.consumer.lag", holder, AtomicLong::get)
                        .description("Committed-offset lag for this consumer group")
                        .tag("group", group)
                        .tag("topic", t)
                        .register(registry);
            }
            return holder;
        });
    }

    @Override
    public void destroy() {
        admin.close(java.time.Duration.ofSeconds(2));
    }
}
