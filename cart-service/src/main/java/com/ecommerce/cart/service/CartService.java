package com.ecommerce.cart.service;

import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartItemResponse;
import com.ecommerce.cart.dto.CartLine;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.dto.CartItemUpdateRequest;
import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.model.CartItem;
import com.ecommerce.cart.model.CartStatus;
import com.ecommerce.cart.client.CatalogClient;
import com.ecommerce.cart.client.CatalogProduct;
import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartItemResponse;
import com.ecommerce.cart.dto.CartLine;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.dto.CartItemUpdateRequest;
import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.model.CartItem;
import com.ecommerce.cart.model.CartStatus;
import com.ecommerce.cart.repository.CartItemRepository;
import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CartService {

    private static final int MAX_ITEM_QUANTITY = 99;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CatalogClient catalogClient;

    public CartService(CartRepository cartRepository,
                       CartItemRepository cartItemRepository,
                       CatalogClient catalogClient) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.catalogClient = catalogClient;
    }

    @Transactional
    public CartResponse getOrCreate(UUID cartId) {
        Cart cart = cartId != null ? requireCart(cartId) : new Cart();
        if (cartId == null) {
            cartRepository.save(cart);
        }
        return toResponse(cart);
    }

    @Transactional
    public CartResponse addItem(CartItemRequest request) {
        Cart cart = requireActiveCart(request.cartId());
        CatalogProduct product = catalogClient.getActiveProduct(request.productId());

        CartItem existing = cartItemRepository.findByCartIdAndProductId(cart.getId(), product.id()).orElse(null);
        if (existing != null) {
            int newQuantity = existing.getQuantity() + request.quantity();
            if (newQuantity > MAX_ITEM_QUANTITY) {
                throw new ConflictException("Item quantity would exceed the maximum of " + MAX_ITEM_QUANTITY);
            }
            existing.changeQuantity(newQuantity);
        } else {
            cartItemRepository.save(new CartItem(cart.getId(), product.id(), product.sku(), request.quantity()));
        }
        return toResponse(cart);
    }

    @Transactional
    public CartResponse updateQuantity(UUID cartId, UUID itemId, CartItemUpdateRequest request) {
        requireActiveCart(cartId);
        CartItem item = cartItemRepository.findByIdAndCartId(itemId, cartId)
                .orElseThrow(() -> new NotFoundException("Cart item not found: " + itemId));
        item.changeQuantity(request.quantity());
        return toResponse(requireCart(cartId));
    }

    @Transactional
    public CartResponse removeItem(UUID cartId, UUID itemId) {
        requireActiveCart(cartId);
        CartItem item = cartItemRepository.findByIdAndCartId(itemId, cartId)
                .orElseThrow(() -> new NotFoundException("Cart item not found: " + itemId));
        cartItemRepository.delete(item);
        return toResponse(requireCart(cartId));
    }

    @Transactional(readOnly = true)
    public List<CartLine> linesForCheckout(UUID cartId) {
        Cart cart = requireCart(cartId);
        if (cart.getStatus() != CartStatus.ACTIVE) {
            throw new ConflictException("Cart is already checked out");
        }
        List<CartItem> items = cartItemRepository.findByCartId(cartId);
        if (items.isEmpty()) {
            throw new ConflictException("Cart is empty");
        }
        return items.stream()
                .map(item -> new CartLine(cart.getId(), item.getProductId(), item.getSku(), item.getQuantity()))
                .toList();
    }

    @Transactional
    public void markCheckedOut(UUID cartId) {
        requireActiveCart(cartId).checkOut();
    }

    private Cart requireActiveCart(UUID cartId) {
        Cart cart = requireCart(cartId);
        if (cart.getStatus() != CartStatus.ACTIVE) {
            throw new ConflictException("Cart is already checked out: " + cartId);
        }
        return cart;
    }

    private Cart requireCart(UUID cartId) {
        return cartRepository.findById(cartId)
                .orElseThrow(() -> new NotFoundException("Cart not found: " + cartId));
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cartItemRepository.findByCartId(cart.getId()).stream()
                .map(this::toItemResponse)
                .toList();
        return new CartResponse(cart.getId(), cart.getCustomerId(), cart.getStatus().name(), items);
    }

    private CartItemResponse toItemResponse(CartItem item) {
        String name = null;
        java.math.BigDecimal unitPrice = null;
        try {
            CatalogProduct product = catalogClient.getActiveProduct(item.getProductId());
            name = product.name();
            unitPrice = product.price();
        } catch (NotFoundException ignored) {
            // product no longer purchasable; cart displays it as unavailable
        }
        return new CartItemResponse(item.getId(), item.getProductId(), item.getSku(), name, item.getQuantity(), unitPrice);
    }
}
