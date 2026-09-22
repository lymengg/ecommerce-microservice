package com.ecommerce.integration;

import com.ecommerce.common.security.KeycloakJwtAuthoritiesConverter;
import com.ecommerce.inventory.dto.ReservationRequest;
import com.ecommerce.inventory.dto.StockRequest;
import com.ecommerce.inventory.model.ReservationStatus;
import com.ecommerce.inventory.repository.InventoryReservationRepository;
import com.ecommerce.inventory.service.InventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the commit-by-order / release-by-order endpoints consumed by the
 * checkout orchestrator during saga completion and compensation.
 */
@AutoConfigureMockMvc
class InventoryInternalControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void commitByOrderCommitsAllReservations() throws Exception {
        inventoryService.initializeStock(new StockRequest(1L, "SKU-INT-1", 10));
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve(new ReservationRequest(1L, 3, orderId));

        mockMvc.perform(post("/internal/api/v1/inventory/reservations/commit-by-order")
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderActionRequest(orderId))))
                .andExpect(status().isNoContent());

        assertThat(inventoryService.getStock(1L).committedQuantity()).isEqualTo(3);
        assertThat(inventoryService.getStock(1L).availableQuantity()).isEqualTo(7);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.COMMITTED)).hasSize(1);
    }

    @Test
    void releaseByOrderReleasesAllReservations() throws Exception {
        inventoryService.initializeStock(new StockRequest(2L, "SKU-INT-2", 5));
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve(new ReservationRequest(2L, 2, orderId));

        mockMvc.perform(post("/internal/api/v1/inventory/reservations/release-by-order")
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderActionRequest(orderId))))
                .andExpect(status().isNoContent());

        assertThat(inventoryService.getStock(2L).availableQuantity()).isEqualTo(5);
        assertThat(inventoryService.getStock(2L).reservedQuantity()).isEqualTo(0);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RELEASED)).hasSize(1);
    }

    /**
     * Phase 7 (ADR-020): the read endpoint the order reconciliation job uses to
     * learn the authoritative reservation state of a stuck order. Read-only, and
     * SERVICE-only like the rest of {@code /internal/**}.
     */
    @Test
    void reservationsByOrderReturnsAuthoritativeState() throws Exception {
        inventoryService.initializeStock(new StockRequest(7L, "SKU-INT-7", 10));
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve(new ReservationRequest(7L, 3, orderId));

        mockMvc.perform(get("/internal/api/v1/inventory/reservations")
                        .param("orderId", orderId.toString())
                        .with(serviceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$[0].status").value("RESERVED"))
                .andExpect(jsonPath("$[0].quantity").value(3));

        mockMvc.perform(get("/internal/api/v1/inventory/reservations")
                        .param("orderId", UUID.randomUUID().toString())
                        .with(serviceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void missingOrderIdIsRejected() throws Exception {
        mockMvc.perform(post("/internal/api/v1/inventory/reservations/commit-by-order")
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsCustomerTokens() throws Exception {
        mockMvc.perform(post("/internal/api/v1/inventory/reservations/commit-by-order")
                        .with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                                .authorities(new KeycloakJwtAuthoritiesConverter()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderActionRequest(UUID.randomUUID()))))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor serviceToken() {
        return jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("SERVICE"))))
                .authorities(new KeycloakJwtAuthoritiesConverter());
    }

    private record OrderActionRequest(UUID orderId) {
    }
}
