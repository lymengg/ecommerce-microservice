package com.ecommerce.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_webhook_events_provider_event", columnNames = {"provider", "provider_event_id"})
})
public class WebhookEvent {

    @Id
    private UUID id;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_event_id", nullable = false, length = 128)
    private String providerEventId;

    @Column(nullable = false, length = 50)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private boolean processed;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    public WebhookEvent() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    public WebhookEvent(String provider, String providerEventId, String type, String payload) {
        this();
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.type = type;
        this.payload = payload;
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isProcessed() {
        return processed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markProcessed() {
        this.processed = true;
    }
}
