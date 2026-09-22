package com.ecommerce.common.resilience;

/**
 * The logical names of the downstream dependencies, shared so that the name a
 * client registers its breaker under and the name used in
 * {@code ecommerce.resilience.dependencies.<name>} configuration cannot drift
 * apart. A typo would otherwise silently give a dependency the global defaults
 * instead of its own policy.
 */
public final class Dependencies {

    public static final String CATALOG = "catalog";
    public static final String CART = "cart";
    public static final String INVENTORY = "inventory";
    public static final String ORDER = "order";
    public static final String PAYMENT = "payment";

    private Dependencies() {
    }
}
