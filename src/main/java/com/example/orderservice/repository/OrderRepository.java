package com.example.orderservice.repository;

import com.example.orderservice.model.Order;
import com.example.orderservice.model.Order.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByCustomerId(String customerId);

    List<Order> findByStatus(OrderStatus status);

    long countByStatus(OrderStatus status);

    @Query("SELECT o FROM Order o WHERE o.customerId = :customerId AND o.status = :status")
    List<Order> findByCustomerIdAndStatus(String customerId, OrderStatus status);
}
