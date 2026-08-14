package com.se.frms.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "se_frms_transaction_master")
public class TransactionMaster {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "external_transaction_id", length = 150)
    private String externalTransactionId;

    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "merchant_id", length = 100)
    private String merchantId;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(length = 50)
    private String channel;

    @Column(name = "transaction_type", length = 100)
    private String transactionType;

    @Column(length = 10)
    private String currency;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "duplicate_transaction")
    private Boolean duplicateTransaction;

    @Column(name = "original_transaction_id")
    private UUID originalTransactionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transaction_data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> transactionData;

    private String remarks;
    private String status;
    private String createdBy;
    private LocalDateTime createdDate;
    private LocalDateTime updatedAt;
}
