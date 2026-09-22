package com.ecommerce.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves one {@link ClientResilience} per downstream dependency from
 * {@link ResilienceProperties}, and owns the Resilience4j registries so the
 * instances can be bound to Micrometer once a registry exists (Phase 8).
 *
 * <p>Instances are created lazily and cached: a dependency that a service never
 * calls never gets a breaker, and the same breaker instance is shared by every
 * call to that dependency within the service — which is the whole point of a
 * circuit breaker.
 */
public class ClientResilienceFactory {

    private static final Logger log = LoggerFactory.getLogger(ClientResilienceFactory.class);

    private final ResilienceProperties properties;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;
    private final ConcurrentMap<String, ClientResilience> resolved = new ConcurrentHashMap<>();

    public ClientResilienceFactory(ResilienceProperties properties) {
        this(properties,
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults());
    }

    public ClientResilienceFactory(ResilienceProperties properties,
                                   CircuitBreakerRegistry circuitBreakers,
                                   RetryRegistry retries,
                                   BulkheadRegistry bulkheads) {
        this.properties = properties;
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.bulkheads = bulkheads;
    }

    /** The policy for a dependency, created on first use and then reused. */
    public ClientResilience forDependency(String dependency) {
        return resolved.computeIfAbsent(dependency, this::resolve);
    }

    public CircuitBreakerRegistry circuitBreakers() {
        return circuitBreakers;
    }

    public RetryRegistry retries() {
        return retries;
    }

    public BulkheadRegistry bulkheads() {
        return bulkheads;
    }

    private ClientResilience resolve(String dependency) {
        ResilienceProperties.Dependency override = properties.getDependencies().get(dependency);

        HttpLimits http = new HttpLimits(
                properties.getHttp().getConnectTimeout(),
                properties.getHttp().getConnectionRequestTimeout(),
                override != null && override.getResponseTimeout() != null
                        ? override.getResponseTimeout()
                        : properties.getHttp().getResponseTimeout(),
                properties.getHttp().getMaxConnectionsPerRoute(),
                properties.getHttp().getMaxConnectionsTotal());

        boolean retryEnabled = override == null || override.getRetryEnabled() == null || override.getRetryEnabled();
        int maxAttempts = override != null && override.getMaxAttempts() != null
                ? override.getMaxAttempts()
                : properties.getRetry().getMaxAttempts();

        int maxConcurrentCalls = override != null && override.getMaxConcurrentCalls() != null
                ? override.getMaxConcurrentCalls()
                : properties.getBulkhead().getMaxConcurrentCalls();

        float failureRateThreshold = override != null && override.getFailureRateThreshold() != null
                ? override.getFailureRateThreshold()
                : properties.getCircuitBreaker().getFailureRateThreshold();

        java.time.Duration waitInOpen = override != null && override.getWaitDurationInOpenState() != null
                ? override.getWaitDurationInOpenState()
                : properties.getCircuitBreaker().getWaitDurationInOpenState();

        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(dependency,
                circuitBreakerConfig(failureRateThreshold, waitInOpen));
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit breaker '{}' transitioned {} -> {}",
                        event.getCircuitBreakerName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));

        // A Retry instance that would never retry is noise: drop it so the
        // interceptor does not decorate at all.
        Retry retry = retryEnabled && maxAttempts > 1
                ? retries.retry(dependency, retryConfig(maxAttempts))
                : null;

        Bulkhead bulkhead = bulkheads.bulkhead(dependency,
                BulkheadConfig.custom()
                        .maxConcurrentCalls(maxConcurrentCalls)
                        .maxWaitDuration(properties.getBulkhead().getMaxWaitDuration())
                        .build());

        return new ClientResilience(dependency, http, circuitBreaker, retry, bulkhead);
    }

    private RetryConfig retryConfig(int maxAttempts) {
        ResilienceProperties.Retry retry = properties.getRetry();
        return RetryConfig.<ClientHttpResponse>custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        retry.getInitialBackoff(), retry.getMultiplier(), retry.getJitter(), retry.getMaxBackoff()))
                // Only transport failures are retryable exceptions. A 4xx never
                // fixes itself, so it is not retried (and is not even an
                // exception at this layer — it is a returned response).
                .retryExceptions(IOException.class)
                .retryOnResult(result -> retry.isRetryOnServerError() && isServerError(result))
                .build();
    }

    private CircuitBreakerConfig circuitBreakerConfig(float failureRateThreshold, java.time.Duration waitInOpen) {
        ResilienceProperties.CircuitBreaker breaker = properties.getCircuitBreaker();
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(breaker.getSlidingWindowSize())
                .minimumNumberOfCalls(breaker.getMinimumNumberOfCalls())
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(waitInOpen)
                .permittedNumberOfCallsInHalfOpenState(breaker.getPermittedNumberOfCallsInHalfOpenState())
                .slowCallDurationThreshold(breaker.getSlowCallDurationThreshold())
                .slowCallRateThreshold(breaker.getSlowCallRateThreshold())
                // A 5xx is a failure of the dependency even though the HTTP call
                // itself succeeded; without this the breaker would only ever see
                // transport exceptions and would not trip on a service that is
                // answering 500s.
                .recordResult(ClientResilienceFactory::isServerError)
                // A full bulkhead is back-pressure we applied ourselves, not a
                // sign the dependency is broken.
                .ignoreExceptions(BulkheadFullException.class)
                .build();
    }

    /**
     * Whether an exchange result is a server error. {@code getStatusCode()} is
     * declared to throw {@link IOException}; a status that cannot be read at all
     * is treated as a failure of the dependency, which is what both callers
     * (record-as-failure, retry) want.
     */
    private static boolean isServerError(Object result) {
        if (!(result instanceof ClientHttpResponse response)) {
            return false;
        }
        try {
            return response.getStatusCode().is5xxServerError();
        } catch (IOException ex) {
            return true;
        }
    }
}
