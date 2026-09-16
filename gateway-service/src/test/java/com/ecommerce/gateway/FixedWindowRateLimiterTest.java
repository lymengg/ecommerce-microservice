package com.ecommerce.gateway;

import com.ecommerce.gateway.filter.FixedWindowRateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    @Test
    void allowsUpToLimitWithinWindow() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("client-1")).isTrue();
        assertThat(limiter.tryAcquire("client-1")).isTrue();
        assertThat(limiter.tryAcquire("client-1")).isTrue();
        assertThat(limiter.tryAcquire("client-1")).isFalse();
    }

    @Test
    void bucketsAreIndependentPerClient() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("client-1")).isTrue();
        assertThat(limiter.tryAcquire("client-1")).isFalse();
        assertThat(limiter.tryAcquire("client-2")).isTrue();
    }

    @Test
    void windowSlidesAfterExpiry() throws InterruptedException {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofMillis(50));

        assertThat(limiter.tryAcquire("client-1")).isTrue();
        assertThat(limiter.tryAcquire("client-1")).isFalse();

        Thread.sleep(80);

        assertThat(limiter.tryAcquire("client-1")).isTrue();
    }
}
