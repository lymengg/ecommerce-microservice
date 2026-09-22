package com.ecommerce.common.client;

import com.ecommerce.common.resilience.BudgetedHttpComponentsClientHttpRequestFactory;
import com.ecommerce.common.resilience.ClientResilience;
import com.ecommerce.common.resilience.HttpLimits;
import com.ecommerce.common.resilience.ResilienceRequestInterceptor;
import com.ecommerce.common.tracing.Correlation;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * Builds the RestClient used by service-to-service clients. Uses Apache
 * HttpClient 5 (rather than the JDK client) because its connection manager
 * handles server-side keep-alive closes cleanly, which matters for sagas that
 * issue several sequential calls to the same service. Error responses (RFC
 * 9457 ProblemDetail bodies) are decoded by {@link RemoteExceptionMapper} from
 * the raw response body, so no custom message converters are required.
 *
 * <p>Since Phase 7 every client is built from a {@link ClientResilience}, which
 * supplies the connect / connection-request / response timeouts, a bounded
 * connection pool per dependency, and that dependency's circuit breaker, retry
 * and bulkhead (ADR-017). Passing the resilience in rather than having each
 * client assemble its own decorators is what keeps the policy uniform — and the
 * dependency name is explicit, so a breaker can never be shared by two
 * services.
 */
public final class RestClients {

    private RestClients() {
    }

    public static RestClient create(String baseUrl, ClientResilience resilience) {
        return builder(baseUrl, resilience, null).build();
    }

    /**
     * Creates a client that attaches a service-to-service bearer token
     * (client credentials, SERVICE role) to every request.
     */
    public static RestClient createWithServiceToken(String baseUrl,
                                                    Supplier<String> bearerToken,
                                                    ClientResilience resilience) {
        return builder(baseUrl, resilience, bearerToken).build();
    }

    private static RestClient.Builder builder(String baseUrl,
                                              ClientResilience resilience,
                                              Supplier<String> bearerToken) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(resilience))
                // Outermost first: the resilience interceptor must wrap the
                // token/correlation interceptor so a retry re-attaches the
                // bearer token (and re-reads the cached one if it rotated).
                .requestInterceptor(new ResilienceRequestInterceptor(resilience))
                .requestInterceptor((request, body, execution) -> {
                    if (bearerToken != null) {
                        request.getHeaders().setBearerAuth(bearerToken.get());
                    }
                    // Forward the edge correlation id so a single id covers the
                    // whole saga. Without this every callee generates its own and
                    // a user-quoted id only finds the first hop.
                    //
                    // Trace context propagates separately and automatically (the
                    // OTel agent handles the traceparent header); this is the
                    // human-facing support id, which tracing deliberately does
                    // not replace.
                    String correlationId = MDC.get(Correlation.MDC_KEY);
                    if (correlationId != null && !correlationId.isBlank()) {
                        request.getHeaders().set(Correlation.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                });
    }

    /**
     * One pooled client per dependency, with the pool bounded on purpose. The
     * bound is the second half of the bulkhead: Resilience4j caps how many
     * calls may be in flight, and the pool caps how many sockets that can turn
     * into — so a saturated dependency cannot hold connections the caller needs
     * for a healthy one.
     */
    private static ClientHttpRequestFactory requestFactory(ClientResilience resilience) {
        HttpLimits limits = resilience.http();
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(limits.maxConnectionsTotal());
        connectionManager.setDefaultMaxPerRoute(limits.maxConnectionsPerRoute());

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();

        return new BudgetedHttpComponentsClientHttpRequestFactory(httpClient, limits);
    }
}
