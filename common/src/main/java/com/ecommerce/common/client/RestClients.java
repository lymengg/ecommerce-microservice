package com.ecommerce.common.client;

import com.ecommerce.common.tracing.Correlation;
import org.slf4j.MDC;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * Builds the RestClient used by service-to-service clients. Uses Apache
 * HttpClient 5 (rather than the JDK client) because its connection manager
 * handles server-side keep-alive closes cleanly, which matters for sagas that
 * issue several sequential calls to the same service. Error responses (RFC
 * 9457 ProblemDetail bodies) are decoded by {@link RemoteExceptionMapper} from
 * the raw response body, so no custom message converters are required.
 */
public final class RestClients {

    private RestClients() {
    }

    public static RestClient create(String baseUrl) {
        return builder(baseUrl).build();
    }

    /**
     * Creates a client that attaches a service-to-service bearer token
     * (client credentials, SERVICE role) to every request.
     */
    public static RestClient createWithServiceToken(String baseUrl, Supplier<String> bearerToken) {
        return builder(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(bearerToken.get());
                    return execution.execute(request, body);
                })
                .build();
    }

    private static RestClient.Builder builder(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory())
                .requestInterceptor((request, body, execution) -> {
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
}
