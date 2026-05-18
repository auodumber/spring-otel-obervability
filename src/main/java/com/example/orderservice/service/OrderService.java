package com.example.orderservice.service;

import com.example.orderservice.dto.CreateOrderRequest;
import com.example.orderservice.dto.UpdateOrderStatusRequest;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.model.Order;
import com.example.orderservice.model.Order.OrderStatus;
import com.example.orderservice.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Business logic layer for Order management.
 *
 * Observability highlights
 * ────────────────────────
 * • @Observed(name="…")   → ObservedAspect creates a child span + Micrometer timer
 *                            for each method automatically.
 *
 * • MDC.put(…)            → Key-value pairs are captured by the Logback OTel appender
 *                            and attached as log record attributes, making them
 *                            queryable in Grafana Loki (e.g. {order_id="…"}).
 *
 * • Timer.record(…)       → Histogram exported via OTLP to Prometheus/Mimir.
 *
 * • Counter.increment()   → Counter exported via OTLP; drives Grafana alerts.
 *
 * • log.info/warn/error   → Correlated to the active trace via trace_id / span_id
 *                            injected into MDC by Micrometer Tracing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final MeterRegistry   meterRegistry;
    private final Counter          ordersCreatedCounter;
    private final Counter          ordersCancelledCounter;
    private final Timer            orderCreationTimer;

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Transactional
    @Observed(name = "order.create",
              contextualName = "create-order",
              lowCardinalityKeyValues = {"layer", "service"})
    public Order createOrder(CreateOrderRequest req) {

        return orderCreationTimer.record(() -> {
            BigDecimal total = req.getUnitPrice()
                                  .multiply(BigDecimal.valueOf(req.getQuantity()));

            Order order = Order.builder()
                    .customerId(req.getCustomerId())
                    .productId(req.getProductId())
                    .productName(req.getProductName())
                    .quantity(req.getQuantity())
                    .unitPrice(req.getUnitPrice())
                    .totalAmount(total)
                    .status(OrderStatus.PENDING)
                    .build();

            Order saved = orderRepository.save(order);

            // Structured MDC context — OTel Logback appender picks this up
            // as log record attributes, fully queryable in Loki.
            MDC.put("order_id",     saved.getId().toString());
            MDC.put("customer_id",  saved.getCustomerId());
            MDC.put("product_id",   saved.getProductId());
            MDC.put("order_status", saved.getStatus().name());
            MDC.put("order_total",  saved.getTotalAmount().toPlainString());

            log.info("Order created successfully");

            // Increment counter with product label for per-product dashboards
            ordersCreatedCounter.increment();
            meterRegistry.counter("orders.created.by.product",
                                  "product_id", saved.getProductId()).increment();

            MDC.remove("order_id");
            MDC.remove("customer_id");
            MDC.remove("product_id");
            MDC.remove("order_status");
            MDC.remove("order_total");

            return saved;
        });
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @Observed(name = "order.get",
              contextualName = "get-order-by-id",
              lowCardinalityKeyValues = {"layer", "service"})
    public Order getOrder(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Observed(name = "order.list",
              contextualName = "list-all-orders",
              lowCardinalityKeyValues = {"layer", "service"})
    public List<Order> listOrders() {
        List<Order> orders = orderRepository.findAll();
        log.debug("Listed {} orders", orders.size());
        return orders;
    }

    @Observed(name = "order.list.by.customer",
              contextualName = "list-orders-by-customer",
              lowCardinalityKeyValues = {"layer", "service"})
    public List<Order> listOrdersByCustomer(String customerId) {
        return orderRepository.findByCustomerId(customerId);
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    @Transactional
    @Observed(name = "order.update.status",
              contextualName = "update-order-status",
              lowCardinalityKeyValues = {"layer", "service"})
    public Order updateStatus(UUID id, UpdateOrderStatusRequest req) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        OrderStatus previousStatus = order.getStatus();
        order.setStatus(req.getStatus());
        Order updated = orderRepository.save(order);

        MDC.put("order_id",         id.toString());
        MDC.put("previous_status",  previousStatus.name());
        MDC.put("new_status",       req.getStatus().name());

        log.info("Order status updated");

        if (req.getStatus() == OrderStatus.CANCELLED) {
            ordersCancelledCounter.increment();
            log.warn("Order cancelled. Reason: {}", req.getReason());
        }

        // Per-transition counter — enables funnel analysis in Grafana
        meterRegistry.counter("orders.status.transition",
                "from", previousStatus.name(),
                "to",   req.getStatus().name()).increment();

        MDC.remove("order_id");
        MDC.remove("previous_status");
        MDC.remove("new_status");

        return updated;
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    @Transactional
    @Observed(name = "order.delete",
              contextualName = "delete-order",
              lowCardinalityKeyValues = {"layer", "service"})
    public void deleteOrder(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        orderRepository.delete(order);
        log.info("Order deleted: orderId={}", id);
    }
}
