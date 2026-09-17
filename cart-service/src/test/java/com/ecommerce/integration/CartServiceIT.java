package com.ecommerce.integration;

import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.common.error.NotFoundException;
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
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

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
        CartResponse cart = cartService.getOrCreate(CUSTOMER_ID);
        cart = cartService.addItem(CUSTOMER_ID, new CartItemRequest(PRODUCT_ID, 2));

        assertThat(cart.status()).isEqualTo("ACTIVE");
        assertThat(cart.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).quantity()).isEqualTo(2);
        assertThat(cart.items().get(0).unitPrice()).isEqualByComparingTo("79.99");

        CartResponse again = cartService.getOrCreate(CUSTOMER_ID);
        assertThat(again.cartId()).isEqualTo(cart.cartId());
        assertThat(again.items()).hasSize(1);
    }

    @Test
    void mutationAfterCheckoutStartsFreshCart() {
        CartResponse first = cartService.getOrCreate(CUSTOMER_ID);
        cartService.markCheckedOut(first.cartId());

        CartResponse second = cartService.addItem(CUSTOMER_ID, new CartItemRequest(PRODUCT_ID, 1));

        assertThat(second.cartId()).isNotEqualTo(first.cartId());
        assertThat(second.status()).isEqualTo("ACTIVE");
        assertThat(second.items()).hasSize(1);
    }

    @Test
    void linesForCheckoutRejectsUnknownCart() {
        assertThatThrownBy(() -> cartService.linesForCheckout(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }
}
