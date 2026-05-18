package com.example.orderservice.controller;

import com.example.orderservice.dto.CreateOrderRequest;
import com.example.orderservice.dto.UpdateOrderStatusRequest;
import com.example.orderservice.model.Order;
import com.example.orderservice.service.OrderService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST API for Order management.
 *
 * Tracing note: Spring Boot's auto-instrumentation (via spring-boot-starter-opentelemetry)
 * automatically creates a root span for every HTTP request handled by Spring MVC.
 * The @Observed annotations here add controller-level child spans on top of the
 * service-layer spans created in OrderService, giving a full call hierarchy in Tempo.
 *
 * Every request/response is logged by the framework interceptors, with trace_id
 * injected into MDC — so every log line in Loki is linkable to its trace in Tempo.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;


    // ------------------------------------------------------------------
    // POST /api/v1/orders
    // ------------------------------------------------------------------

    @PostMapping
    @Observed(name = "http.order.create", contextualName = "POST /api/v1/orders")
    public ResponseEntity<Order> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        log.info("Received create-order request for customer={}", request.getCustomerId());
        Order created = orderService.createOrder(request);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/orders
    // ------------------------------------------------------------------

    @GetMapping
    @Observed(name = "http.order.list", contextualName = "GET /api/v1/orders")
    public ResponseEntity<List<Order>> listOrders(
            @RequestParam(required = false) String customerId) {

        List<Order> orders = (customerId != null)
                ? orderService.listOrdersByCustomer(customerId)
                : orderService.listOrders();

        log.debug("Returning {} orders", orders.size());
        return ResponseEntity.ok(orders);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/orders/{id}
    // ------------------------------------------------------------------

    @GetMapping("/{id}")
    @Observed(name = "http.order.get", contextualName = "GET /api/v1/orders/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/orders/{id}/status
    // ------------------------------------------------------------------

    @PutMapping("/{id}/status")
    @Observed(name = "http.order.update.status",
              contextualName = "PUT /api/v1/orders/{id}/status")
    public ResponseEntity<Order> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {

        log.info("Status update requested: orderId={} newStatus={}", id, request.getStatus());
        return ResponseEntity.ok(orderService.updateStatus(id, request));
    }

    // ------------------------------------------------------------------
    // DELETE /api/v1/orders/{id}
    // ------------------------------------------------------------------

    @DeleteMapping("/{id}")
    @Observed(name = "http.order.delete", contextualName = "DELETE /api/v1/orders/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable UUID id) {
        orderService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }
}
