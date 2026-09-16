package com.ecommerce.common.client;

import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.InsufficientStockException;
import com.ecommerce.common.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteExceptionMapperTest {

    @Test
    void mapsNotFoundToNotFoundException() {
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(),
                "{\"title\":\"Not Found\",\"status\":404,\"detail\":\"Product not found: 42\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        RuntimeException mapped = RemoteExceptionMapper.from(ex);

        assertThat(mapped).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Product not found: 42");
    }

    @Test
    void mapsConflictToConflictException() {
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", problemHeaders(),
                "{\"title\":\"Conflict\",\"status\":409,\"detail\":\"Cart is already checked out\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        RuntimeException mapped = RemoteExceptionMapper.from(ex);

        assertThat(mapped).isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cart is already checked out");
    }

    @Test
    void mapsInsufficientStockProblemToInsufficientStockException() {
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", problemHeaders(),
                ("{\"type\":\"urn:problem:insufficient-stock\",\"title\":\"Insufficient Stock\"," +
                        "\"status\":409,\"detail\":\"Insufficient stock for product 7\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        RuntimeException mapped = RemoteExceptionMapper.from(ex);

        assertThat(mapped).isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock for product 7");
    }

    @Test
    void passesThroughUnknownStatus() {
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(),
                "upstream exploded".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        RuntimeException mapped = RemoteExceptionMapper.from(ex);

        assertThat(mapped).isSameAs(ex);
    }

    private static HttpHeaders problemHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return headers;
    }
}
