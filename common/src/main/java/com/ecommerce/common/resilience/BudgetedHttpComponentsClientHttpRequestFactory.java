package com.ecommerce.common.resilience;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Duration;

/**
 * Apache HttpClient 5 request factory that applies the dependency's connect /
 * connection-request / response timeouts and additionally caps the response
 * timeout at the remaining {@link Deadline} budget.
 *
 * <p>The cap is what makes the budget real rather than advisory: the last call
 * of a saga gets whatever is left, not the full configured timeout, so a saga
 * cannot overrun its allowance by a whole socket timeout.
 *
 * <p>Before Phase 7 the factory was a bare
 * {@code new HttpComponentsClientHttpRequestFactory()} with no timeouts at all,
 * which is why a hung dependency blocked the calling thread indefinitely.
 */
public class BudgetedHttpComponentsClientHttpRequestFactory extends HttpComponentsClientHttpRequestFactory {

    public BudgetedHttpComponentsClientHttpRequestFactory(HttpClient httpClient, HttpLimits limits) {
        super(httpClient);
        setConnectTimeout(limits.connectTimeout());
        setConnectionRequestTimeout(limits.connectionRequestTimeout());
        setReadTimeout(limits.responseTimeout());
    }

    @Override
    protected RequestConfig createRequestConfig(Object request) {
        RequestConfig base = super.createRequestConfig(request);
        Duration remaining = Deadline.remaining().orElse(null);
        if (remaining == null) {
            return base;
        }
        Timeout configured = base.getResponseTimeout();
        if (configured == null) {
            return RequestConfig.copy(base).setResponseTimeout(Timeout.of(remaining)).build();
        }
        long remainingMillis = remaining.toMillis();
        if (remainingMillis >= configured.toMilliseconds()) {
            return base;
        }
        // At least 1 ms: an already-spent budget must still produce a timeout
        // rather than a request that is allowed to run unbounded.
        return RequestConfig.copy(base)
                .setResponseTimeout(Timeout.ofMilliseconds(Math.max(1, remainingMillis)))
                .build();
    }
}
