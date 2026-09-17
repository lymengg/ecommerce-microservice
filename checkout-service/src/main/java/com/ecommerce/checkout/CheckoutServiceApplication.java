package com.ecommerce.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stateless checkout orchestrator: drives the checkout saga over synchronous
 * REST (cart -> order -> inventory -> payment) and compensates on failure.
 * Scans only the checkout package plus shared error handling and the shared
 * OAuth2 resource-server security config — the outbox and persistence live in
 * the domain services, not here.
 */
@SpringBootApplication(scanBasePackages = {
        "com.ecommerce.checkout",
        "com.ecommerce.common.error",
        "com.ecommerce.common.security"
})
public class CheckoutServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckoutServiceApplication.class, args);
    }
}
