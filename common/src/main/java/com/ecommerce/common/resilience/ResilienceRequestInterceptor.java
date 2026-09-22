package com.ecommerce.common.resilience;

import com.ecommerce.common.error.ServiceUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Applies one dependency's resilience policy to every exchange made through a
 * client: bulkhead, then circuit breaker, then (opt-in) retry.
 *
 * <p>Order matters and is the Resilience4j-recommended one — bulkhead
 * innermost, retry outermost — because it means each retry attempt must
 * re-acquire a bulkhead permit and pass the breaker, so a retry storm cannot
 * bypass the guards that exist to stop it. The reverse order would let the
 * retry decorator queue up attempts behind a saturated bulkhead.
 *
 * <p>Failure translation is deliberate. Everything that means "the dependency
 * could not answer" — a timeout, a connection failure, pool or bulkhead
 * exhaustion, an open breaker, or a 5xx that survived the retries — becomes
 * {@link ServiceUnavailableException} (HTTP 503), which the checkout saga treats
 * as a reason to compensate. A 4xx is deliberately left alone: it is a business
 * answer and still travels the existing {@code RemoteExceptionMapper} path.
 */
public final class ResilienceRequestInterceptor implements ClientHttpRequestInterceptor {

    private final ClientResilience resilience;

    public ResilienceRequestInterceptor(ClientResilience resilience) {
        this.resilience = resilience;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (Deadline.current().filter(Deadline::isExpired).isPresent()) {
            throw new ServiceUnavailableException(
                    "Request budget exhausted before calling " + resilience.dependency());
        }

        CheckedSupplier<ClientHttpResponse> decorated = decorate(request, () -> execution.execute(request, body));
        ClientHttpResponse response;
        try {
            response = decorated.get();
        } catch (CallNotPermittedException ex) {
            throw new ServiceUnavailableException(
                    "Circuit breaker is open for " + resilience.dependency(), ex);
        } catch (BulkheadFullException ex) {
            throw new ServiceUnavailableException(
                    "Bulkhead is saturated for " + resilience.dependency(), ex);
        } catch (IOException ex) {
            throw new ServiceUnavailableException(
                    "Could not reach " + resilience.dependency() + " within its timeout budget: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new ServiceUnavailableException("Call to " + resilience.dependency() + " failed", ex);
        }

        // A 5xx that survived the retries means the dependency could not answer,
        // which is the same class of failure as a timeout — so it gets the same
        // typed error. This is what closes the "unreachable dependency strands
        // the order" gap for 5xx and not just for transport failures: the saga
        // compensates on ServiceUnavailableException, and a raw
        // RestClientResponseException would slip past it. 4xx is deliberately
        // left alone: it is a business answer and still travels the existing
        // RemoteExceptionMapper path.
        if (response.getStatusCode().is5xxServerError()) {
            int status = response.getStatusCode().value();
            response.close();
            throw new ServiceUnavailableException(
                    resilience.dependency() + " answered " + status + " after the retry budget");
        }
        return response;
    }

    private CheckedSupplier<ClientHttpResponse> decorate(HttpRequest request,
                                                         CheckedSupplier<ClientHttpResponse> call) {
        CheckedSupplier<ClientHttpResponse> decorated = call;
        if (resilience.bulkhead() != null) {
            decorated = Bulkhead.decorateCheckedSupplier(resilience.bulkhead(), decorated);
        }
        if (resilience.circuitBreaker() != null) {
            decorated = CircuitBreaker.decorateCheckedSupplier(resilience.circuitBreaker(), decorated);
        }
        if (retryApplies(request)) {
            decorated = Retry.decorateCheckedSupplier(resilience.retry(), decorated);
        }
        return decorated;
    }

    /**
     * Retries are opt-in per request (ADR-019). Safe methods default to
     * retryable; a state-changing call is only retried when the client asserts
     * the operation is idempotent with {@link Retryable#yes}.
     */
    private boolean retryApplies(HttpRequest request) {
        Retry retry = resilience.retry();
        if (retry == null) {
            return false;
        }
        Object declared = request.getAttributes().get(Retryable.ATTRIBUTE);
        if (declared instanceof Boolean retryable) {
            return retryable;
        }
        HttpMethod method = request.getMethod();
        return method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS;
    }
}
