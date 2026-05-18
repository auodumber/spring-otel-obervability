package com.example.orderservice.dto;

import com.example.orderservice.model.Order.OrderStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateOrderStatusRequest {

    @NotNull(message = "Status is required")
    private OrderStatus status;

    private String reason;
}
