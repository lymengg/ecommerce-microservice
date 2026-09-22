package com.ecommerce.common.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resilience policy for the cross-service REST clients (Phase 7, ADR-017).
 *
 * <p>Every value has a default that is safe on a developer machine, and every
 * value is overridable per dependency under
 * {@code ecommerce.resilience.dependencies.<name>.*}. Per-dependency
 * granularity is not cosmetic: a single global circuit breaker would let a bad
 * payment-service open the circuit for inventory too (ADR-017 §granularity).
 *
 * <p>The HTTP numbers are a budget, not independent knobs. See
 * {@code ecommerce.resilience.saga-budget} and the arithmetic in ADR-018: the
 * per-call worst case (connect + attempts × response timeout + backoff) must
 * fit inside the saga budget, and the saga budget must fit inside the gateway's
 * response timeout.
 */
@ConfigurationProperties("ecommerce.resilience")
public class ResilienceProperties {

    private Http http = new Http();
    private Retry retry = new Retry();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Bulkhead bulkhead = new Bulkhead();

    /**
     * Overall wall-clock budget for one checkout saga. Enforced by
     * {@link Deadline}: the saga refuses to start a call it cannot finish and
     * compensates instead. Must stay below the gateway's response timeout so a
     * bounded saga failure is reported to the client rather than cut off at the
     * edge.
     */
    private Duration sagaBudget = Duration.ofSeconds(10);

    /** Per-dependency overrides, keyed by the logical dependency name
     * ({@code catalog}, {@code cart}, {@code inventory}, {@code order},
     * {@code payment}). */
    private Map<String, Dependency> dependencies = new LinkedHashMap<>();

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public Bulkhead getBulkhead() {
        return bulkhead;
    }

    public void setBulkhead(Bulkhead bulkhead) {
        this.bulkhead = bulkhead;
    }

    public Duration getSagaBudget() {
        return sagaBudget;
    }

    public void setSagaBudget(Duration sagaBudget) {
        this.sagaBudget = sagaBudget;
    }

    public Map<String, Dependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(Map<String, Dependency> dependencies) {
        this.dependencies = dependencies;
    }

    /** Transport-level limits, applied to the Apache HttpClient 5 client. */
    public static class Http {

        /** Time allowed to establish the TCP connection. */
        private Duration connectTimeout = Duration.ofMillis(300);

        /**
         * Time allowed to lease a connection from the pool. This is the limit
         * that turns "the pool is exhausted" into a fast, typed failure instead
         * of an unbounded wait — the Phase 7 blast-radius guard.
         */
        private Duration connectionRequestTimeout = Duration.ofMillis(300);

        /**
         * Time allowed to read the response (the socket timeout). Capped by the
         * remaining saga budget at request time.
         */
        private Duration responseTimeout = Duration.ofMillis(1500);

        /**
         * Bounded pool per target (ADR-017 §bulkheads). Small enough that one
         * saturated dependency cannot hold more than this many connections;
         * each client owns its own pool, so the bound is per dependency.
         */
        private int maxConnectionsPerRoute = 20;

        private int maxConnectionsTotal = 100;

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getConnectionRequestTimeout() {
            return connectionRequestTimeout;
        }

        public void setConnectionRequestTimeout(Duration connectionRequestTimeout) {
            this.connectionRequestTimeout = connectionRequestTimeout;
        }

        public Duration getResponseTimeout() {
            return responseTimeout;
        }

        public void setResponseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
        }

        public int getMaxConnectionsPerRoute() {
            return maxConnectionsPerRoute;
        }

        public void setMaxConnectionsPerRoute(int maxConnectionsPerRoute) {
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
        }

        public int getMaxConnectionsTotal() {
            return maxConnectionsTotal;
        }

        public void setMaxConnectionsTotal(int maxConnectionsTotal) {
            this.maxConnectionsTotal = maxConnectionsTotal;
        }
    }

    /** Bounded retry with exponential backoff and jitter. */
    public static class Retry {

        /** Total attempts, including the first. 3 means "try, then retry twice". */
        private int maxAttempts = 3;

        private Duration initialBackoff = Duration.ofMillis(100);

        private double multiplier = 2.0;

        private Duration maxBackoff = Duration.ofMillis(500);

        /**
         * Jitter as a fraction of the computed backoff. Without it every
         * caller that failed at the same instant retries at the same instant,
         * which is the retry storm this phase exists to prevent.
         */
        private double jitter = 0.5;

        /** Retry a 5xx response (a 4xx never fixes itself). */
        private boolean retryOnServerError = true;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }

        public boolean isRetryOnServerError() {
            return retryOnServerError;
        }

        public void setRetryOnServerError(boolean retryOnServerError) {
            this.retryOnServerError = retryOnServerError;
        }
    }

    /** Circuit breaker thresholds. */
    public static class CircuitBreaker {

        private int slidingWindowSize = 20;

        /** Below this many calls the breaker stays CLOSED regardless of failures. */
        private int minimumNumberOfCalls = 10;

        private float failureRateThreshold = 50f;

        /**
         * How long the breaker stays OPEN before probing with HALF_OPEN calls.
         * Long enough that a struggling dependency is not hammered, short
         * enough that recovery is automatic.
         */
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);

        private int permittedNumberOfCallsInHalfOpenState = 3;

        /**
         * Slow-call detection. The rate threshold is left at 100% (i.e. off) on
         * purpose: the response timeout already bounds latency, and a slow-call
         * rule on top of it would open breakers for work that completed.
         */
        private Duration slowCallDurationThreshold = Duration.ofSeconds(2);

        private float slowCallRateThreshold = 100f;

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }

        public Duration getSlowCallDurationThreshold() {
            return slowCallDurationThreshold;
        }

        public void setSlowCallDurationThreshold(Duration slowCallDurationThreshold) {
            this.slowCallDurationThreshold = slowCallDurationThreshold;
        }

        public float getSlowCallRateThreshold() {
            return slowCallRateThreshold;
        }

        public void setSlowCallRateThreshold(float slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
        }
    }

    /** Semaphore bulkhead: a cap on concurrent in-flight calls per dependency. */
    public static class Bulkhead {

        private int maxConcurrentCalls = 20;

        /**
         * Zero: when the bulkhead is full the call fails immediately rather than
         * queueing. Queueing would just move the pile-up from the connection
         * pool into the caller's thread pool, which is the failure being fixed.
         */
        private Duration maxWaitDuration = Duration.ZERO;

        public int getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        public Duration getMaxWaitDuration() {
            return maxWaitDuration;
        }

        public void setMaxWaitDuration(Duration maxWaitDuration) {
            this.maxWaitDuration = maxWaitDuration;
        }
    }

    /**
     * Overrides for one dependency. Every field is nullable; {@code null} means
     * "inherit the global default above", so a service only states what is
     * genuinely different about a dependency.
     */
    public static class Dependency {

        /**
         * Whether retries are allowed for this dependency at all. Individual
         * calls still opt in per request (see {@code Retryable}), because a GET
         * and a state-changing POST to the same service do not share a retry
         * policy.
         */
        private Boolean retryEnabled;

        /** Overrides {@link Http#getResponseTimeout()}. */
        private Duration responseTimeout;

        /** Overrides {@link Retry#getMaxAttempts()}. */
        private Integer maxAttempts;

        /** Overrides {@link Bulkhead#getMaxConcurrentCalls()}. */
        private Integer maxConcurrentCalls;

        /** Overrides {@link CircuitBreaker#getWaitDurationInOpenState()}. */
        private Duration waitDurationInOpenState;

        /** Overrides {@link CircuitBreaker#getFailureRateThreshold()}. */
        private Float failureRateThreshold;

        public Boolean getRetryEnabled() {
            return retryEnabled;
        }

        public void setRetryEnabled(Boolean retryEnabled) {
            this.retryEnabled = retryEnabled;
        }

        public Duration getResponseTimeout() {
            return responseTimeout;
        }

        public void setResponseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Integer getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(Integer maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public Float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(Float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }
    }
}
