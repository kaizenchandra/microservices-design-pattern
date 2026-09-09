package com.synechis.fulfillment.contracts;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record PlaceOrder(@NotBlank @Pattern(regexp = "[A-Z0-9-]{1,40}") String sku,
                         @Min(1) @Max(100) int quantity,
                         @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                         @Pattern(regexp = "USD|EUR|INR") @NotNull String currency,
                         @Pattern(regexp = "SUCCESS|REJECT|TIMEOUT_BEFORE|TIMEOUT_AFTER|AMBIGUOUS|COMPENSATION_RETRY") @NotNull String paymentMode,
                         @Pattern(regexp = "SUCCESS|REJECT|TIMEOUT_BEFORE|TIMEOUT_AFTER|AMBIGUOUS|COMPENSATION_RETRY") @NotNull String shippingMode) {
}
