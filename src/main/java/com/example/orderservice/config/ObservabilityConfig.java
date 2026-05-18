package com.example.orderservice.config;

import com.example.orderservice.model.Order.OrderStatus;
import com.example.orderservice.repository.OrderRepository;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralised observability wiring:
 *
 *  1. ObservedAspect  – activates @Observed on any Spring bean so every
 *                       annotated method emits a span + Micrometer timer.
 *
 *  2. MeterRegistryCustomizer – attaches common tags (env, region) to
 *                       every metric exported to Prometheus / OTLP.
 *
 *  3. Order-domain gauges – gauge the live count of PENDING orders so
 *                       Grafana alerts fire when the queue grows too large.
 *
 *  4. Custom Counters / Timers are declared here but *incremented* in
 *                       OrderService to keep business logic explicit.
 */
@Configuration
@EnableScheduling
@Slf4j
public class ObservabilityConfig {


    private OrderRepository repo;
    private AtomicLong pendingOrdersGauge =  new AtomicLong();
    private AtomicLong processingOrdersGauge =  new AtomicLong();

    public ObservabilityConfig(OrderRepository repo) {
        this.repo = repo;
    }

    // -------------------------------------------------------------------------
    // 1. Enable @Observed AOP support
    // -------------------------------------------------------------------------

    /**
     * Registers the AOP advice that intercepts methods annotated with
     * {@code @Observed} and creates an Observation (which maps to both a
     * Micrometer timer and an OpenTelemetry span via the OTLP bridge).
     */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }

    // -------------------------------------------------------------------------
    // 2. Common tags on every metric
    // -------------------------------------------------------------------------

    /**
     * Adds environment, region and app-version tags to every meter.
     * In production these come from environment variables / K8s downward API.
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config()
                .commonTags(
                    "environment", System.getenv().getOrDefault("APP_ENV",       "local"),
                    "region",      System.getenv().getOrDefault("APP_REGION",    "ap-south-1"),
                    "version",     System.getenv().getOrDefault("APP_VERSION",   "1.0.0")
                );
    }

    // -------------------------------------------------------------------------
    // 3. Domain-specific gauges (live counts of orders per status)
    // -------------------------------------------------------------------------

    /**
     * Gauge counters that are refreshed on a schedule via
      *
     * Using AtomicLong + MultiGauge pattern keeps the gauges registered once
     * and avoids duplicate-metric exceptions on refresh.
     */
    @Bean
    AtomicLong pendingOrdersGauge(MeterRegistry registry) {
        AtomicLong count = new AtomicLong(0);
        Gauge.builder("orders.pending.count", count, AtomicLong::get)
             .description("Number of orders currently in PENDING status")
             .register(registry);
        return count;
    }

    @Bean
    AtomicLong processingOrdersGauge(MeterRegistry registry) {
        AtomicLong count = new AtomicLong(0);
        Gauge.builder("orders.processing.count", count, AtomicLong::get)
             .description("Number of orders currently in PROCESSING status")
             .register(registry);
        return count;
    }

    // -------------------------------------------------------------------------
    // 4. Custom named Counters exposed as beans for injection in OrderService
    // -------------------------------------------------------------------------

    @Bean
    Counter ordersCreatedCounter(MeterRegistry registry) {
        return Counter.builder("orders.created.total")
                .description("Total number of orders created")
                .register(registry);
    }

    @Bean
    Counter ordersCancelledCounter(MeterRegistry registry) {
        return Counter.builder("orders.cancelled.total")
                .description("Total number of orders cancelled")
                .register(registry);
    }

    /**
     * Timer for the end-to-end order creation path, including DB write.
     * Use LongTaskTimer for operations that could run > 1 minute.
     */
    @Bean
    Timer orderCreationTimer(MeterRegistry registry) {
        return Timer.builder("orders.creation.duration")
                .description("Time taken to create and persist an order")
                .publishPercentiles(0.5, 0.90, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    // -------------------------------------------------------------------------
    // Scheduled gauge refresh (every 30 s)
    // -------------------------------------------------------------------------

    @Scheduled(fixedRateString = "${app.metrics.gauge-refresh-ms:30000}")
    void refreshOrderGauges() {

        long pending    = repo.countByStatus(OrderStatus.PENDING);
        long processing = repo.countByStatus(OrderStatus.PROCESSING);

        pendingOrdersGauge.set(pending);
        processingOrdersGauge.set(processing);

        log.debug("Refreshed order gauges: pending={}, processing={}", pending, processing);
    }
}
