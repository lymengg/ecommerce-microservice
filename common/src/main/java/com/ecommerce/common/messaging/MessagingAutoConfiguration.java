package com.ecommerce.common.messaging;

import com.ecommerce.common.outbox.OutboxPublisher;
import com.ecommerce.common.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Wires the shared event-messaging components (Phase 6, ADR-015).
 *
 * <p>Auto-configured rather than component-scanned for the same reason as
 * {@link com.ecommerce.common.tracing.TracingAutoConfiguration}: a service's
 * component scan must not decide whether reliability machinery exists.
 * {@code @ConditionalOnClass(KafkaTemplate)} means it activates only in
 * services that declare {@code spring-kafka} — catalog, cart and checkout never
 * open a broker connection.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConsumerIdempotency consumerIdempotency(ProcessedEventRepository processedEventRepository) {
        return new ConsumerIdempotency(processedEventRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "ecommerce.outbox.enabled", matchIfMissing = true)
    public OutboxPublisher outboxPublisher(OutboxRepository outboxRepository,
                                           KafkaTemplate<String, String> kafkaTemplate,
                                           ObjectMapper objectMapper,
                                           Environment environment,
                                           @Value("${ecommerce.outbox.batch-size:100}") int batchSize) {
        String producerName = environment.getProperty("spring.application.name", "unknown-service");
        return new OutboxPublisher(outboxRepository, kafkaTemplate, objectMapper, producerName, batchSize);
    }

    @Bean
    @ConditionalOnMissingBean
    public RecordInterceptor<String, String> correlationRecordInterceptor() {
        return new CorrelationRecordInterceptor();
    }

    /**
     * Bounded retries with exponential backoff, then a dead-letter topic
     * (doc 06 §6-7). Without this a single poison message would be retried
     * forever and wedge the partition — no later message on that partition
     * would ever be processed.
     *
     * <p>Three retries at 500 ms, 1 s, 2 s (capped at 5 s), then
     * {@code <topic>.DLT}. The original record is republished to the DLT with
     * its headers intact, so it can be inspected and replayed once the bug is
     * fixed.
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Route to our declared <topic>.DLT rather than Spring Kafka's default
        // <topic>-dlt suffix, so the dead-letter topics match doc 06 §6 and the
        // ones KafkaAdmin creates.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(Topics.deadLetter(record.topic()), record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setCommitRecovered(true);
        return handler;
    }

    /**
     * Declares the domain topics (and their dead-letter twins) so they exist
     * with the right partition count instead of being auto-created with broker
     * defaults. {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE=false} on the broker
     * makes a typo fail loudly rather than silently create a topic.
     */
    @Bean
    public KafkaAdmin.NewTopics domainEventTopics() {
        return new KafkaAdmin.NewTopics(
                topic(Topics.ORDER), topic(Topics.deadLetter(Topics.ORDER)),
                topic(Topics.PAYMENT), topic(Topics.deadLetter(Topics.PAYMENT)),
                topic(Topics.INVENTORY), topic(Topics.deadLetter(Topics.INVENTORY)),
                topic(Topics.CATALOG), topic(Topics.deadLetter(Topics.CATALOG)),
                topic(Topics.CART), topic(Topics.deadLetter(Topics.CART)));
    }

    private static org.apache.kafka.clients.admin.NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
