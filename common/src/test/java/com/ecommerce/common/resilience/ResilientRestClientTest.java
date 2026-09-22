package com.ecommerce.common.resilience;

import com.ecommerce.common.client.RestClients;
import com.ecommerce.common.error.ServiceUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 7's "prove the failure is gone" suite (ADR-017/018/019).
 *
 * <p>Each test reproduces one of the failure modes the phase exists to remove —
 * an unbounded wait on a hung dependency, a retry storm against a struggling
 * one, a saturated dependency starving a healthy one, a 4xx being retried — and
 * asserts the bounded behaviour instead. No Docker and no Spring context: these
 * are the fast guard rails, while the end-to-end failure injection lives in the
 * service ITs.
 *
 * <p>Uses the JDK HTTP server rather than WireMock so `common` keeps no
 * test-scoped server dependency.
 */
class ResilientRestClientTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger concurrent = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private volatile Duration handlerDelay = Duration.ZERO;
    private volatile int handlerStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        serverExecutor = Executors.newFixedThreadPool(64);
        server.setExecutor(serverExecutor);
        server.createContext("/dep", this::handle);
        // A healthy endpoint on the same host, so a test can show that one
        // dependency's failure does not affect another.
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
        Deadline.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        lastMethod.set(exchange.getRequestMethod());
        int now = concurrent.incrementAndGet();
        maxConcurrent.accumulateAndGet(now, Math::max);
        try {
            if (!handlerDelay.isZero()) {
                Thread.sleep(handlerDelay.toMillis());
            }
            exchange.sendResponseHeaders(handlerStatus, -1);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            concurrent.decrementAndGet();
            exchange.close();
        }
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /**
     * A short-timeout, small-window policy so the tests are fast and the
     * breaker trips on a handful of calls.
     */
    private ClientResilienceFactory factory() {
        ResilienceProperties properties = new ResilienceProperties();
        properties.getHttp().setConnectTimeout(Duration.ofMillis(500));
        properties.getHttp().setConnectionRequestTimeout(Duration.ofMillis(500));
        properties.getHttp().setResponseTimeout(Duration.ofMillis(250));
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoff(Duration.ofMillis(10));
        properties.getRetry().setMaxBackoff(Duration.ofMillis(20));
        properties.getRetry().setJitter(0.1);
        properties.getCircuitBreaker().setSlidingWindowSize(10);
        properties.getCircuitBreaker().setMinimumNumberOfCalls(5);
        properties.getCircuitBreaker().setFailureRateThreshold(50f);
        properties.getCircuitBreaker().setWaitDurationInOpenState(Duration.ofSeconds(30));
        properties.getBulkhead().setMaxConcurrentCalls(4);
        return new ClientResilienceFactory(properties);
    }

    private RestClient client(ClientResilienceFactory factory, String dependency) {
        return RestClients.create(baseUrl(), factory.forDependency(dependency));
    }

    // --- 7a: timeouts -----------------------------------------------------

    @Test
    void aHungDependencyFailsFastInsteadOfBlockingTheCaller() {
        handlerDelay = Duration.ofSeconds(10);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client(factory(), "inventory").get().uri("/dep").retrieve().toBodilessEntity())
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("inventory");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Response timeout is 250 ms; retries are bounded to 3 attempts with
        // 10-20 ms backoff, so the whole thing must finish well under 2 s even
        // though the server would have taken 10 s.
        assertThat(elapsedMs).isLessThan(2_000);
    }

    @Test
    void anExpiredBudgetRefusesToStartAnotherCall() {
        Deadline.start(Duration.ofMillis(50));
        try {
            Thread.sleep(120);
            assertThatThrownBy(() -> client(factory(), "order").get().uri("/dep").retrieve().toBodilessEntity())
                    .isInstanceOf(ServiceUnavailableException.class)
                    .hasMessageContaining("budget exhausted");
            assertThat(requests.get()).isZero();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            Deadline.clear();
        }
    }

    // --- 7b: retries ------------------------------------------------------

    @Test
    void retriesServerErrorsUpToTheAttemptCap() {
        handlerStatus = 503;

        assertThatThrownBy(() -> client(factory(), "payment").get().uri("/dep").retrieve().toBodilessEntity())
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("answered 503");

        // maxAttempts = 3: try, retry, retry, then give up.
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test
    void neverRetriesAClientError() {
        handlerStatus = 400;

        assertThatThrownBy(() -> client(factory(), "catalog").get().uri("/dep").retrieve().toBodilessEntity())
                .isInstanceOf(RestClientResponseException.class);

        // A 400 will not fix itself; retrying it is pure amplification.
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void retriesAStateChangingCallOnlyWhenTheClientOptsIn() {
        handlerStatus = 503;

        assertThatThrownBy(() -> client(factory(), "order").post().uri("/dep").retrieve().toBodilessEntity())
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(requests.get())
                .as("an unmarked POST is not retried: the caller has not asserted it is idempotent")
                .isEqualTo(1);

        requests.set(0);
        assertThatThrownBy(() -> Retryable.yes(client(factory(), "order").post().uri("/dep"))
                .retrieve().toBodilessEntity())
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(requests.get())
                .as("an explicitly idempotent POST is retried")
                .isEqualTo(3);
    }

    @Test
    void retryBackoffGrowsAndStaysBounded() {
        handlerStatus = 503;
        ResilienceProperties properties = new ResilienceProperties();
        properties.getHttp().setResponseTimeout(Duration.ofMillis(250));
        properties.getRetry().setMaxAttempts(4);
        properties.getRetry().setInitialBackoff(Duration.ofMillis(50));
        properties.getRetry().setMultiplier(2.0);
        properties.getRetry().setMaxBackoff(Duration.ofMillis(80));
        properties.getRetry().setJitter(0.0);

        long start = System.nanoTime();
        assertThatThrownBy(() -> RestClients.create(baseUrl(), new ClientResilienceFactory(properties)
                .forDependency("payment")).get().uri("/dep").retrieve().toBodilessEntity())
                .isInstanceOf(ServiceUnavailableException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(requests.get()).isEqualTo(4);
        // 50 + 80 + 80 = 210 ms of capped backoff; anything unbounded would blow
        // well past this.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(200).isLessThan(2_000);
    }

    // --- 7b: circuit breaker ---------------------------------------------

    @Test
    void theBreakerOpensAfterRepeatedFailuresAndThenFailsFastWithoutCallingTheDependency() {
        handlerStatus = 500;
        ClientResilienceFactory factory = factory();
        RestClient client = client(factory, "inventory");

        for (int i = 0; i < 5; i++) {
            try {
                client.get().uri("/dep").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // expected: 5xx, retried, then returned
            }
        }
        CircuitBreaker breaker = factory.forDependency("inventory").circuitBreaker();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int requestsBefore = requests.get();

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/dep").retrieve().toBodilessEntity())
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Circuit breaker is open");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("an open breaker fails immediately").isLessThan(200);
        assertThat(requests.get()).as("the dependency is not called at all").isEqualTo(requestsBefore);
    }

    @Test
    void breakersArePerDependencySoOneBadDependencyDoesNotOpenTheCircuitForAnother() {
        handlerStatus = 500;
        ClientResilienceFactory factory = factory();
        RestClient bad = client(factory, "payment");
        RestClient good = client(factory, "inventory");

        for (int i = 0; i < 5; i++) {
            try {
                bad.get().uri("/dep").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // expected
            }
        }

        assertThat(factory.forDependency("payment").circuitBreaker().getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(factory.forDependency("inventory").circuitBreaker().getState())
                .as("a global breaker would have taken inventory down too")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(good.get().uri("/ok").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
    }

    /**
     * A dependency that is slow but answering correctly must not open the
     * circuit. This is the Phase 8 baseline's finding, pinned: a legitimate
     * 2.2 s provider call (inside its own 3 s timeout) produced a 45 % checkout
     * error rate, because slow-call detection was configured below the response
     * timeout and every call was therefore "slow".
     */
    @Test
    void aSlowButSuccessfulCallDoesNotOpenTheBreaker() {
        ResilienceProperties properties = new ResilienceProperties();
        properties.getHttp().setResponseTimeout(Duration.ofSeconds(1));
        properties.getRetry().setMaxAttempts(1);
        properties.getCircuitBreaker().setSlidingWindowSize(10);
        properties.getCircuitBreaker().setMinimumNumberOfCalls(5);
        properties.getCircuitBreaker().setFailureRateThreshold(50f);
        ClientResilienceFactory factory = new ClientResilienceFactory(properties);

        handlerDelay = Duration.ofMillis(400);
        RestClient client = client(factory, "payment");

        for (int i = 0; i < 6; i++) {
            assertThat(client.get().uri("/dep").retrieve().toBodilessEntity().getStatusCode().value())
                    .as("slow, but successful")
                    .isEqualTo(200);
        }

        assertThat(factory.forDependency("payment").circuitBreaker().getState())
                .as("slowness is an SLO symptom, not a reason to deny service")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * The mechanism behind that bug, demonstrated deliberately: with the
     * slow-call threshold below the response timeout, calls that *succeed* are
     * counted as failures and the breaker opens. This is why the default is
     * Resilience4j's 60 s and why lowering it is documented as a trap.
     */
    @Test
    void aSlowCallThresholdBelowTheResponseTimeoutConvertsLatencyIntoErrors() {
        ResilienceProperties properties = new ResilienceProperties();
        properties.getHttp().setResponseTimeout(Duration.ofSeconds(2));
        properties.getRetry().setMaxAttempts(1);
        properties.getCircuitBreaker().setSlidingWindowSize(8);
        properties.getCircuitBreaker().setMinimumNumberOfCalls(4);
        properties.getCircuitBreaker().setFailureRateThreshold(50f);
        properties.getCircuitBreaker().setSlowCallDurationThreshold(Duration.ofMillis(100));
        properties.getCircuitBreaker().setSlowCallRateThreshold(100f);
        ClientResilienceFactory factory = new ClientResilienceFactory(properties);

        handlerDelay = Duration.ofMillis(200);
        RestClient client = client(factory, "payment");

        for (int i = 0; i < 5; i++) {
            try {
                client.get().uri("/dep").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // not expected: every one of these calls succeeds
            }
        }

        assertThat(factory.forDependency("payment").circuitBreaker().getState())
                .as("100% is a reachable rate, not the same as disabled")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    // --- 7c: bulkheads ----------------------------------------------------

    @Test
    void aSaturatedDependencyFailsFastAndDoesNotStarveAHealthyOne() throws Exception {
        // A dedicated policy: retries off (so every caller makes exactly one
        // request) and a response timeout comfortably above the handler delay
        // (so no call times out, and the permit is held for the whole call —
        // otherwise a timed-out client would release its permit while the
        // server was still working and the server-side count would not reflect
        // the bulkhead).
        ResilienceProperties properties = new ResilienceProperties();
        properties.getHttp().setResponseTimeout(Duration.ofSeconds(5));
        properties.getRetry().setMaxAttempts(1);
        properties.getBulkhead().setMaxConcurrentCalls(4);
        ClientResilienceFactory factory = new ClientResilienceFactory(properties);

        handlerDelay = Duration.ofMillis(1_000);
        RestClient saturated = client(factory, "inventory");

        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread thread = new Thread(() -> {
                try {
                    saturated.get().uri("/dep").retrieve().toBodilessEntity();
                } catch (Exception ignored) {
                    // not expected here
                }
            });
            thread.start();
            callers.add(thread);
        }
        Thread.sleep(300);

        // The fifth call to the saturated dependency is rejected immediately
        // rather than queued behind the others.
        long start = System.nanoTime();
        assertThatThrownBy(() -> saturated.get().uri("/dep").retrieve().toBodilessEntity())
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Bulkhead is saturated");
        assertThat((System.nanoTime() - start) / 1_000_000)
                .as("back-pressure is immediate, not queued into the caller's thread pool")
                .isLessThan(300);

        // A healthy dependency called by the same caller is unaffected.
        long healthyStart = System.nanoTime();
        assertThat(client(factory, "catalog").get().uri("/ok").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(200);
        assertThat((System.nanoTime() - healthyStart) / 1_000_000)
                .as("the healthy dependency is not starved by the saturated one")
                .isLessThan(1_000);

        for (Thread caller : callers) {
            caller.join(TimeUnit.SECONDS.toMillis(15));
        }
        assertThat(maxConcurrent.get()).as("the bulkhead bounds in-flight calls").isLessThanOrEqualTo(4);
    }

    // --- configuration ----------------------------------------------------

    @Test
    void perDependencyOverridesBeatTheGlobalDefaults() {
        ResilienceProperties properties = new ResilienceProperties();
        properties.getHttp().setResponseTimeout(Duration.ofSeconds(1));
        properties.getHttp().setMaxConnectionsPerRoute(10);
        properties.getRetry().setMaxAttempts(3);

        ResilienceProperties.Dependency order = new ResilienceProperties.Dependency();
        order.setResponseTimeout(Duration.ofSeconds(5));
        order.setMaxAttempts(1);
        order.setMaxConcurrentCalls(2);
        properties.getDependencies().put("order", order);

        ClientResilienceFactory factory = new ClientResilienceFactory(properties);

        ClientResilience overridden = factory.forDependency("order");
        assertThat(overridden.http().responseTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(overridden.bulkhead().getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(2);
        assertThat(overridden.retry()).as("maxAttempts = 1 means no retry decorator at all").isNull();

        ClientResilience defaulted = factory.forDependency("catalog");
        assertThat(defaulted.http().responseTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaulted.http().maxConnectionsPerRoute()).isEqualTo(10);
        assertThat(defaulted.retry()).isNotNull();
        assertThat(factory.forDependency("order")).isSameAs(overridden);
    }

    @Test
    void retriesCanBeDisabledForADependency() {
        ResilienceProperties properties = new ResilienceProperties();
        ResilienceProperties.Dependency inventory = new ResilienceProperties.Dependency();
        inventory.setRetryEnabled(false);
        properties.getDependencies().put("inventory", inventory);

        assertThat(new ClientResilienceFactory(properties).forDependency("inventory").retry()).isNull();
    }

    @Test
    void sendsTheRightMethod() {
        handlerStatus = 200;
        client(factory(), "catalog").get().uri("/dep").retrieve().toBodilessEntity();
        assertThat(lastMethod.get()).isEqualTo("GET");
    }
}
