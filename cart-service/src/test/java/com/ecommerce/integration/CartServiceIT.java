package com.ecommerce.integration;

import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartServiceIT extends AbstractIntegrationTest {

    private static final long PRODUCT_ID = 1L;

    @Autowired
    private CartService cartService;

    @BeforeEach
    void stubCatalogProduct() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/internal/api/v1/catalog/products/" + PRODUCT_ID))
                .willReturn(okJson("""
                        {"id":1,"sku":"SKU-CART-1","name":"Keyboard","description":"Mechanical",
                         "price":79.99,"status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z"}
                        """)));
    }

    @Test
    void addAndRetrieveCartWithEnrichedPrices() {
        CartResponse cart = cartService.getOrCreate(null);
        cart = cartService.addItem(new CartItemRequest(cart.cartId(), PRODUCT_ID, 2));

        assertThat(cart.status()).isEqualTo("ACTIVE");
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).quantity()).isEqualTo(2);
        assertThat(cart.items().get(0).unitPrice()).isEqualByComparingTo("79.99");

        cart = cartService.getOrCreate(cart.cartId());
        assertThat(cart.items()).hasSize(1);
    }

    @Test
    void checkedOutCartCannotAcceptItems() {
        CartResponse cart = cartService.getOrCreate(null);
        cartService.markCheckedOut(cart.cartId());

        assertThatThrownBy(() -> cartService.addItem(new CartItemRequest(cart.cartId(), PRODUCT_ID, 1)))
                .isInstanceOf(com.ecommerce.common.error.ConflictException.class);
    }

    @Test
    void unknownCartIsNotFound() {
        assertThatThrownBy(() -> cartService.getOrCreate(UUID.randomUUID()))
                .isInstanceOf(com.ecommerce.common.error.NotFoundException.class);
    }
}
