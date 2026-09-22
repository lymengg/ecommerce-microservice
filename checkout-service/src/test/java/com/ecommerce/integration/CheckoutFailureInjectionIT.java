package com.ecommerce.integration;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7e — the doc 10 §7 failure cases that belong to the saga. Every one of
 * these used to end with an order stranded in {@code PENDING} and stock held
 * until its TTL, because the saga only compensated for a business rejection and
 * had no timeouts to bound the wait.
 *
 * <p>Faults are injected with WireMock's fault modes against the stubbed
 * downstream services; nothing mocks the broker or the database.
 */
class CheckoutFailureInjectionIT extends AbstractCheckoutIT {

    private void stubSagaUpToReservation() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/internal/api/v1/cart/" + CART_ID + "/lines"))
                .willReturn(okJson(cartLinesJson())));
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(okJson(orderJson("DRAFT"))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/orders/" + ORDER_ID + "/pending"))
                .willReturn(okJson(orderJson("PENDING"))));
    }

    private void stubCompensation() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order"))
                .willReturn(noContent()));
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/cancel"))
                .willReturn(okJson(orderJson("CANCELLED"))));
    }

    /**
     * The gap the phase exists to close: inventory answers 5xx, and the order
     * must be compensated rather than left in PENDING holding stock.
     */
    @Test
    void inventoryServerErrorCompensatesInsteadOfStrandingTheOrder() throws Exception {
        stubSagaUpToReservation();
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations"))
                .willReturn(serverError()));
        stubCompensation();

        checkout(null).andExpect(status().isServiceUnavailable());

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/cancel")));
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/api/v1/payments")));
    }

    /** A dropped connection is the same class of failure as a 5xx here. */
    @Test
    void inventoryConnectionFaultCompensatesInsteadOfStrandingTheOrder() throws Exception {
        stubSagaUpToReservation();
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        stubCompensation();

        checkout(null).andExpect(status().isServiceUnavailable());

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/cancel")));
    }

    /**
     * A network timeout must be bounded by the timeout budget and must not
     * cascade: the caller gets a 503 in bounded time instead of waiting for the
     * dependency to recover.
     */
    @Test
    void aSlowInventoryTimesOutWithinTheBudgetAndCompensates() throws Exception {
        stubSagaUpToReservation();
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations"))
                .willReturn(aResponse().withFixedDelay(5_000).withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));
        stubCompensation();

        long start = System.nanoTime();
        checkout(null).andExpect(status().isServiceUnavailable());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("bounded by the timeout budget, not by the dependency")
                .isLessThan(4_000);
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/cancel")));
    }

    /**
     * A payment timeout is the case where compensation would be *wrong*: the
     * payment may have been recorded even though the response never arrived.
     * The order must be left recoverable for reconciliation rather than
     * cancelled, and the failure must not cascade.
     */
    @Test
    void aPaymentTimeoutLeavesTheOrderRecoverableRatherThanCancellingAPossibleCharge() throws Exception {
        stubSagaUpToReservation();
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations"))
                .willReturn(okJson("""
                        {"reservationId":"%s","productId":1,"quantity":2,"orderId":"%s",
                         "status":"RESERVED","expiresAt":"2026-01-01T00:30:00Z"}
                        """.formatted(java.util.UUID.randomUUID(), ORDER_ID))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/orders/" + ORDER_ID + "/payment-pending"))
                .willReturn(okJson(orderJson("PAYMENT_PENDING"))));
        // 30 s, not 5 s: the point is that the saga returns *long* before the
        // dependency would have. With a 5 s provider delay against a 5 s saga
        // budget the two are indistinguishable, and the assertion below could
        // not tell a bounded failure from an unbounded one.
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(aResponse().withFixedDelay(30_000).withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(paymentJson("SUCCEEDED"))));
        stubCompensation();

        long start = System.nanoTime();
        checkout(null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service Unavailable"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Bounded by the saga budget (5 s in this suite) plus at most one
        // in-flight socket timeout — NOT by a tight number that happens to hold
        // on an idle machine. The payment leg is last, so it may legitimately
        // consume whatever is left of the budget; asserting less than that would
        // make the test fail for a correct implementation under load, which is
        // exactly what it did before this comment existed.
        assertThat(elapsedMs)
                .as("bounded by the saga budget, not by the 30s the dependency would have taken")
                .isLessThan(6_000);
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/api/v1/orders/" + ORDER_ID + "/cancel")));
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
    }

    /**
     * Repeated failures must open the breaker, after which the dependency is not
     * called at all and the caller fails immediately — the difference between
     * failing fast and failing slowly.
     */
    @Test
    void repeatedPaymentFailuresOpenTheBreakerAndLaterCheckoutsFailFast() throws Exception {
        stubSagaUpToReservation();
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations"))
                .willReturn(okJson("""
                        {"reservationId":"%s","productId":1,"quantity":2,"orderId":"%s",
                         "status":"RESERVED","expiresAt":"2026-01-01T00:30:00Z"}
                        """.formatted(java.util.UUID.randomUUID(), ORDER_ID))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/orders/" + ORDER_ID + "/payment-pending"))
                .willReturn(okJson(orderJson("PAYMENT_PENDING"))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/v1/payments")).willReturn(serverError()));

        for (int attempt = 0; attempt < 12; attempt++) {
            checkout(null).andExpect(status().isServiceUnavailable());
        }
        int requestsWhenOpen = WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/api/v1/payments"))).size();

        long start = System.nanoTime();
        checkout(null).andExpect(status().isServiceUnavailable());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("an open breaker fails immediately").isLessThan(300);
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/api/v1/payments"))))
                .as("the struggling dependency is not called at all while the breaker is open")
                .hasSize(requestsWhenOpen);
    }

    /** A read failure before anything was created has nothing to compensate. */
    @Test
    void aCartReadFailureIsReportedWithoutSideEffects() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/internal/api/v1/cart/" + CART_ID + "/lines"))
                .willReturn(serverError()));

        checkout(null).andExpect(status().isServiceUnavailable());

        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/api/v1/orders")));
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/api/v1/payments")));
    }

    /**
     * A hung order-service must not hold the saga open indefinitely: the saga
     * deadline stops it spending its whole allowance on one call.
     */
    @Test
    void theSagaBudgetBoundsAChainOfSlowCalls() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/internal/api/v1/cart/" + CART_ID + "/lines"))
                .willReturn(aResponse().withFixedDelay(5_000).withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(cartLinesJson())));

        long start = System.nanoTime();
        checkout(null).andExpect(status().isServiceUnavailable());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("the saga budget is 5s in this suite; the first call cannot overrun it")
                .isLessThan(4_000);
    }
}
