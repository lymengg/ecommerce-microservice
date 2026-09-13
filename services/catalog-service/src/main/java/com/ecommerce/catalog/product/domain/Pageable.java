package com.ecommerce.catalog.product.domain;

public class Pageable {

    private final int page;
    private final int size;

    private Pageable(int page, int size) {
        this.page = page;
        this.size = size;
    }

    public static Pageable of(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Size must be at least 1");
        }
        if (size > 100) {
            throw new IllegalArgumentException("Size must not exceed 100");
        }
        return new Pageable(page, size);
    }

    public static Pageable of(int page, int size, int maxSize) {
        if (page < 0) {
            throw new IllegalArgumentException("Page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Size must be at least 1");
        }
        if (size > maxSize) {
            throw new IllegalArgumentException("Size must not exceed " + maxSize);
        }
        return new Pageable(page, size);
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }
}
