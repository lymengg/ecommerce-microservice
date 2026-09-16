package com.ecommerce.common.client;

import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory())
                .build();
    }
}
