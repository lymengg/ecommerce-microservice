package com.ecommerce.cart.service;

import com.ecommerce.cart.client.CatalogClient;
import com.ecommerce.cart.client.CatalogProduct;
import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartItemUpdateRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.model.CartItem;
import com.ecommerce.cart.model.CartStatus;
import com.ecommerce.cart.repository.CartItemRepository;
import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartServiceTest {

    private final CartRepository cartRepository = mock(CartRepository.class);
    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final CatalogClient catalogClient = mock(CatalogClient.class);
    private final CartService cartService = new CartService(cartRepository, cartItemRepository, catalogClient);

    private final UUID customerId = UUID.randomUUID();
    private Cart cart;

    @BeforeEach
    void setUp() {
        cart = new Cart(customerId);
        when(cartRepository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cartRepository.findById(cart.getId())).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(catalogClient.getActiveProduct(1L)).thenReturn(new CatalogProduct(1L, "SKU-1", "Widget", new BigDecimal("10.00")));
    }

    @Test
    void getOrCreateCreatesCartWhenNoneExists() {
        when(cartRepository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.empty());

        CartResponse response = cartService.getOrCreate(customerId);

        assertThat(response.cartId()).isNotNull();
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.status()).isEqualTo(CartStatus.ACTIVE.name());
        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    void getOrCreateReturnsExistingActiveCart() {
        CartResponse response = cartService.getOrCreate(customerId);

        assertThat(response.cartId()).isEqualTo(cart.getId());
        verify(cartRepository, never()).save(any(Cart.class));
    }

    @Test
    void addItemAddsNewProduct() {
        when(cartItemRepository.findByCartIdAndProductId(cart.getId(), 1L)).thenReturn(Optional.empty());
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(
                List.of(new CartItem(cart.getId(), 1L, "SKU-1", 2)));

        CartResponse response = cartService.addItem(customerId, new CartItemRequest(1L, 2));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).quantity()).isEqualTo(2);
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    void addItemMergesDuplicateProduct() {
        CartItem existing = new CartItem(cart.getId(), 1L, "SKU-1", 2);
        when(cartItemRepository.findByCartIdAndProductId(cart.getId(), 1L)).thenReturn(Optional.of(existing));
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(existing));

        cartService.addItem(customerId, new CartItemRequest(1L, 3));

        assertThat(existing.getQuantity()).isEqualTo(5);
    }

    @Test
    void addItemRejectsQuantityAboveCap() {
        CartItem existing = new CartItem(cart.getId(), 1L, "SKU-1", 98);
        when(cartItemRepository.findByCartIdAndProductId(cart.getId(), 1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> cartService.addItem(customerId, new CartItemRequest(1L, 3)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void addItemAfterCheckoutStartsFreshCart() {
        cart.checkOut();
        when(cartRepository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.empty());

        CartResponse response = cartService.addItem(customerId, new CartItemRequest(1L, 1));

        assertThat(response.cartId()).isNotEqualTo(cart.getId());
        assertThat(response.status()).isEqualTo(CartStatus.ACTIVE.name());
        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    void updateQuantityChangesQuantity() {
        CartItem item = new CartItem(cart.getId(), 1L, "SKU-1", 2);
        when(cartItemRepository.findByIdAndCartId(item.getId(), cart.getId())).thenReturn(Optional.of(item));

        cartService.updateQuantity(customerId, item.getId(), new CartItemUpdateRequest(7));

        assertThat(item.getQuantity()).isEqualTo(7);
    }

    @Test
    void updateQuantityRejectsItemFromOtherCart() {
        when(cartItemRepository.findByIdAndCartId(UUID.randomUUID(), cart.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.updateQuantity(customerId, UUID.randomUUID(), new CartItemUpdateRequest(1)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void removeItemDeletesItem() {
        CartItem item = new CartItem(cart.getId(), 1L, "SKU-1", 2);
        when(cartItemRepository.findByIdAndCartId(item.getId(), cart.getId())).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of());

        CartResponse response = cartService.removeItem(customerId, item.getId());

        assertThat(response.items()).isEmpty();
        verify(cartItemRepository).delete(item);
    }

    @Test
    void linesForCheckoutRejectsEmptyCart() {
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> cartService.linesForCheckout(cart.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void markCheckedOutChangesStatus() {
        cartService.markCheckedOut(cart.getId());

        assertThat(cart.getStatus()).isEqualTo(CartStatus.CHECKED_OUT);
    }

    @Test
    void inactiveProductCannotBeAdded() {
        when(catalogClient.getActiveProduct(9L)).thenThrow(new NotFoundException("Product not available: 9"));

        assertThatThrownBy(() -> cartService.addItem(customerId, new CartItemRequest(9L, 1)))
                .isInstanceOf(NotFoundException.class);
    }
}
