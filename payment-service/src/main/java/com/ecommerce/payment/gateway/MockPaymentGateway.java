package com.ecommerce.payment.gateway;

import com.ecommerce.payment.model.PaymentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * Mock provider: succeeds unless the amount exceeds a configurable threshold,
 * which simulates a declined payment. Transaction ids are unique per call.
 *
 * <p>Phase 8 added {@code ecommerce.payment.provider-delay}: a slow *provider* is
 * the degradation the observability phase needs, and it belongs here rather than
 * in a chaos endpoint bolted onto the service, because simulating provider
 * behaviour is exactly what a mock provider is for. With a delay set, a checkout
 * gets slower without failing — which is what a latency SLO is supposed to
 * notice — and the resilience timeout turns a long enough delay into a 503,
 * which is what the error-rate SLO is supposed to notice.
 *
 * <p>Default is zero, so the normal flow and the test suite are unaffected.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private final BigDecimal declineAbove;
    private final Duration providerDelay;

    public MockPaymentGateway(@Value("${ecommerce.payment.decline-above:10000}") BigDecimal declineAbove,
                              @Value("${ecommerce.payment.provider-delay:0s}") Duration providerDelay) {
        this.declineAbove = declineAbove;
        this.providerDelay = providerDelay;
    }

    @Override
    public PaymentResult process(PaymentRequest request) {
        if (!providerDelay.isZero() && !providerDelay.isNegative()) {
            try {
                Thread.sleep(providerDelay.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while simulating provider latency", ex);
            }
        }
        PaymentStatus status = request.amount().compareTo(declineAbove) > 0
                ? PaymentStatus.FAILED
                : PaymentStatus.SUCCEEDED;
        return new PaymentResult(status, "mock-txn-" + UUID.randomUUID());
    }
}
