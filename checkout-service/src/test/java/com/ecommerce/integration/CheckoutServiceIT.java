package com.ecommerce.integration;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cross-service checkout saga covered end-to-end: success, payment-failed
 * compensation, insufficient-stock compensation, and idempotent retry.
 */
class CheckoutServiceIT extends AbstractCheckoutIT {

    private void stubHappyPath() {
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlEqualTo("/internal/api/v1/cart/" + CART_ID + "/lines"))
                .willReturn(okJson(cartLinesJson())));
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(okJson(orderJson("DRAFT"))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/orders/" + ORDER_ID + "/pending"))
                .willReturn(okJson(orderJson("PENDING"))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations"))
                .willReturn(okJson("""
                        {"reservationId":"%s","productId":1,"quantity":2,"orderId":"%s",
                         "status":"RESERVED","expiresAt":"2026-01-01T00:30:00Z"}
                        """.formatted(java.util.UUID.randomUUID(), ORDER_ID))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/orders/" + ORDER_ID + "/payment-pending"))
                .willReturn(okJson(orderJson("PAYMENT_PENDING"))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(okJson(paymentJson("SUCCEEDED"))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations/commit-by-order"))
                .willReturn(noContent()));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/orders/" + ORDER_ID + "/paid"))
                .willReturn(okJson(orderJson("PAID"))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/cart/" + CART_ID + "/checkout"))
                .willReturn(noContent()));
    }

    @Test
    void successfulCheckoutCommitsStockAndClosesCart() throws Exception {
        stubHappyPath();

        checkout(null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"));

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/commit-by-order")));
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/internal/api/v1/orders/" + ORDER_ID + "/paid")));
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/internal/api/v1/cart/" + CART_ID + "/checkout")));
    }

    @Test
    void failedPaymentCompensatesInventoryAndCancelsOrder() throws Exception {
        stubHappyPath();
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(okJson(paymentJson("FAILED"))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order"))
                .willReturn(noContent()));
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/cancel"))
                .willReturn(okJson(orderJson("CANCELLED"))));

        checkout(null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("payment declined")));

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/cancel")));
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/commit-by-order")));
    }

    @Test
    void insufficientStockFailsFastAndCompensates() throws Exception {
        stubHappyPath();
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations"))
                .willReturn(aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(insufficientStockProblemJson())));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order"))
                .willReturn(noContent()));
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/cancel"))
                .willReturn(okJson(orderJson("CANCELLED"))));

        checkout(null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title", containsString("Insufficient Stock")));

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/cancel")));
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/api/v1/payments")));
    }

    @Test
    void idempotentRetryReusesAlreadyPaidOrder() throws Exception {
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlEqualTo("/internal/api/v1/cart/" + CART_ID + "/lines"))
                .willReturn(okJson(cartLinesJson())));
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(okJson(orderJson("PAID")))); // idempotent replay returns the paid order
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(okJson(paymentJson("SUCCEEDED"))));

        checkout("checkout-key-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("PAID"));

        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/commit-by-order")));
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/internal/api/v1/orders/" + ORDER_ID + "/paid")));
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo("/api/v1/payments")));
    }
}
