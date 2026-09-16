package com.ecommerce.payment.gateway;

/**
 * Outbound port for payment providers. The modular monolith ships with a mock
 * implementation; a real provider adapter (Stripe/PayPal/...) can be swapped in
 * without touching the payment domain logic.
 */
public interface PaymentGateway {

    String PROVIDER_NAME = "mock";

    PaymentResult process(PaymentRequest request);
}
