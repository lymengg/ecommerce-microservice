package com.ecommerce.integration;

import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.catalog.model.Product;
import com.ecommerce.catalog.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CartServiceIT extends AbstractIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void addAndRetrieveCartWithEnrichedPrices() {
        Product product = productRepository.save(new Product("SKU-CART-1", "Keyboard", "Mechanical", new BigDecimal("79.99")));
        product.activate();
        productRepository.save(product);

        CartResponse cart = cartService.getOrCreate(null);
        cart = cartService.addItem(new CartItemRequest(cart.cartId(), product.getId(), 2));

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

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cartService.addItem(new CartItemRequest(cart.cartId(), 1L, 1)))
                .isInstanceOf(com.ecommerce.common.error.ConflictException.class);
    }

    @Test
    void unknownCartIsNotFound() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cartService.getOrCreate(UUID.randomUUID()))
                .isInstanceOf(com.ecommerce.common.error.NotFoundException.class);
    }
}
