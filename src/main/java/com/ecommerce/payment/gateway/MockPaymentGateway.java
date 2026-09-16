package com.ecommerce.payment.gateway;

import com.ecommerce.payment.model.PaymentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mock provider: succeeds unless the amount exceeds a configurable threshold,
 * which simulates a declined payment. Transaction ids are unique per call.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private final BigDecimal declineAbove;

    public MockPaymentGateway(@Value("${ecommerce.payment.decline-above:10000}") BigDecimal declineAbove) {
        this.declineAbove = declineAbove;
    }

    @Override
    public PaymentResult process(PaymentRequest request) {
        PaymentStatus status = request.amount().compareTo(declineAbove) > 0
                ? PaymentStatus.FAILED
                : PaymentStatus.SUCCEEDED;
        return new PaymentResult(status, "mock-txn-" + UUID.randomUUID());
    }
}
