package com.example.orderservice.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateOrderRequest {

    @NotBlank(message = "Customer ID is required")
    private String customerId;

    @NotBlank(message = "Product ID is required")
    private String productId;

    @NotBlank(message = "Product name is required")
    private String productName;

    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 1000, message = "Quantity cannot exceed 1000")
    private int quantity;

    @NotNull(message = "Unit price is required")
    @DecimalMin(value = "0.01", message = "Unit price must be positive")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal unitPrice;
}
