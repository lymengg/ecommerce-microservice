package com.ecommerce.integration;

import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.catalog.model.Product;
import com.ecommerce.catalog.repository.ProductRepository;
import com.ecommerce.checkout.dto.CheckoutRequest;
import com.ecommerce.checkout.dto.CheckoutResponse;
import com.ecommerce.checkout.service.CheckoutService;
import com.ecommerce.common.error.ConflictException;
import com.ecommerce.inventory.dto.StockRequest;
import com.ecommerce.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutServiceIT extends AbstractIntegrationTest {

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private CartService cartService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductRepository productRepository;

    private long createActiveProduct(String sku, String price) {
        Product product = productRepository.save(new Product(sku, sku + " product", null, new BigDecimal(price)));
        product.activate();
        productRepository.save(product);
        inventoryService.initializeStock(new StockRequest(product.getId(), sku, 10));
        return product.getId();
    }

    @Test
    void successfulCheckoutCommitsStockAndClosesCart() {
        long productId = createActiveProduct("SKU-CO-1", "100.00");
        CartResponse cart = cartService.getOrCreate(null);
        cartService.addItem(new CartItemRequest(cart.cartId(), productId, 2));

        CheckoutResponse result = checkoutService.checkout(new CheckoutRequest(cart.cartId(), null, "USD"), null);

        assertThat(result.orderStatus()).isEqualTo("PAID");
        assertThat(result.paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(cartService.getOrCreate(cart.cartId()).status()).isEqualTo("CHECKED_OUT");
        assertThat(inventoryService.getStock(productId).committedQuantity()).isEqualTo(2);
        assertThat(inventoryService.getStock(productId).availableQuantity()).isEqualTo(8);
    }

    @Test
    void failedPaymentCompensatesInventoryAndCancelsOrder() {
        long productId = createActiveProduct("SKU-CO-2", "50000.00"); // above the mock decline threshold
        CartResponse cart = cartService.getOrCreate(null);
        cartService.addItem(new CartItemRequest(cart.cartId(), productId, 1));

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cart.cartId(), null, "USD"), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("payment declined");

        // compensation: stock fully available again, no reservations left
        assertThat(inventoryService.getStock(productId).availableQuantity()).isEqualTo(10);
        assertThat(inventoryService.getStock(productId).reservedQuantity()).isEqualTo(0);
    }

    @Test
    void insufficientStockFailsFastAndCompensates() {
        long productId = createActiveProduct("SKU-CO-3", "10.00");
        CartResponse cart = cartService.getOrCreate(null);
        cartService.addItem(new CartItemRequest(cart.cartId(), productId, 99)); // only 10 in stock

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cart.cartId(), null, "USD"), null))
                .isInstanceOf(com.ecommerce.common.error.InsufficientStockException.class);

        assertThat(inventoryService.getStock(productId).availableQuantity()).isEqualTo(10);
    }

    @Test
    void duplicateCheckoutReturnsSameResult() {
        long productId = createActiveProduct("SKU-CO-4", "25.00");
        CartResponse cart = cartService.getOrCreate(null);
        cartService.addItem(new CartItemRequest(cart.cartId(), productId, 1));

        CheckoutResponse first = checkoutService.checkout(
                new CheckoutRequest(cart.cartId(), null, "USD"), "checkout-key-1");

        // the cart is closed, so the retry only works at the order level; reuse via idempotency on a fresh cart
        CartResponse cart2 = cartService.getOrCreate(null);
        cartService.addItem(new CartItemRequest(cart2.cartId(), productId, 1));
        CheckoutResponse second = checkoutService.checkout(
                new CheckoutRequest(cart2.cartId(), null, "USD"), "checkout-key-1");

        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(second.orderStatus()).isEqualTo("PAID");
        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        // the retry reuses the already-paid order; stock was committed once
        assertThat(inventoryService.getStock(productId).committedQuantity()).isEqualTo(1);
        assertThat(inventoryService.getStock(productId).availableQuantity()).isEqualTo(9);
    }
}
