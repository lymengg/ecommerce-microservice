package com.ecommerce.common.messaging;

import com.ecommerce.common.tracing.Correlation;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Puts the producer's correlation id into the consumer's MDC for the duration
 * of the record, so a consumed event logs with the same {@code corr=} value as
 * the request that caused it (doc 08 §2, ADR-014).
 *
 * <p>Trace context needs no help here — the OpenTelemetry agent extracts the
 * {@code traceparent} header and continues the trace automatically. The
 * correlation id is our own header, so we carry it across by hand.
 *
 * <p>Registered as a bean; Spring Boot applies it to the
 * {@code kafkaListenerContainerFactory}, so every {@code @KafkaListener} gets
 * it without repeating the code.
 */
public class CorrelationRecordInterceptor implements RecordInterceptor<String, String> {

    @Override
    public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record,
                                                    Consumer<String, String> consumer) {
        Header header = record.headers().lastHeader(Correlation.HEADER);
        if (header != null && header.value() != null) {
            MDC.put(Correlation.MDC_KEY, new String(header.value(), StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        MDC.remove(Correlation.MDC_KEY);
    }
}
