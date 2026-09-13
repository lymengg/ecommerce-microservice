package com.ecommerce.catalog.product.application;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String message) {
        super(message);
    }
}
