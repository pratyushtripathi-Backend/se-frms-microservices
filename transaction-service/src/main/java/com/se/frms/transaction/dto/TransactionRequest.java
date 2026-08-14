package com.se.frms.transaction.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.Map;

public record TransactionRequest(
        @NotBlank(message = "externalTransactionId is required")
        @Size(max = 150, message = "externalTransactionId must not exceed 150 characters")
        String externalTransactionId,

        @Pattern(
                regexp = "^$|^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.|$)){4}$|^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$",
                message = "ipAddress must be a valid IPv4 or IPv6 address"
        )
        String ipAddress,

        @DecimalMin(value = "-90.0", message = "latitude must be greater than or equal to -90")
        @DecimalMax(value = "90.0", message = "latitude must be less than or equal to 90")
        BigDecimal latitude,

        @DecimalMin(value = "-180.0", message = "longitude must be greater than or equal to -180")
        @DecimalMax(value = "180.0", message = "longitude must be less than or equal to 180")
        BigDecimal longitude,

        @NotBlank(message = "merchantId is required")
        @Size(max = 100, message = "merchantId must not exceed 100 characters")
        String merchantId,

        @NotBlank(message = "userId is required")
        @Size(max = 100, message = "userId must not exceed 100 characters")
        String userId,

        @NotBlank(message = "channel is required")
        @Size(max = 50, message = "channel must not exceed 50 characters")
        String channel,

        @NotBlank(message = "transactionType is required")
        @Size(max = 100, message = "transactionType must not exceed 100 characters")
        String transactionType,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a valid 3-letter ISO code")
        String currency,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        BigDecimal amount,

        @NotEmpty(message = "transactionData is required") Map<String, Object> transactionData,
        String remarks,
        String createdBy
) {
}
