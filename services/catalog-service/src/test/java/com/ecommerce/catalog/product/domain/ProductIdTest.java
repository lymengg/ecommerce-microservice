package com.ecommerce.catalog.product.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductIdTest {

    @Test
    void shouldGenerateUniqueId() {
        ProductId id1 = ProductId.generate();
        ProductId id2 = ProductId.generate();

        assertNotEquals(id1.getValue(), id2.getValue());
    }

    @Test
    void shouldCreateFromValidString() {
        ProductId id = ProductId.of("123e4567-e89b-12d3-a456-426614174000");

        assertEquals("123e4567-e89b-12d3-a456-426614174000", id.getValue());
    }

    @Test
    void shouldThrowExceptionWhenValueIsNull() {
        assertThrows(NullPointerException.class, () -> ProductId.of(null));
    }

    @Test
    void shouldThrowExceptionWhenValueIsBlank() {
        assertThrows(IllegalArgumentException.class, () -> ProductId.of(""));
    }

    @Test
    void shouldEqualSameValue() {
        ProductId id1 = ProductId.of("123");
        ProductId id2 = ProductId.of("123");

        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());
    }

    @Test
    void shouldNotEqualDifferentValues() {
        ProductId id1 = ProductId.of("123");
        ProductId id2 = ProductId.of("456");

        assertNotEquals(id1, id2);
    }
}
