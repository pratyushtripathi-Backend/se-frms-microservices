package com.se.frms.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import java.util.Map;
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transaction_data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> transactionData;
    private String remarks;
    private String status;
    private String createdBy;
    private LocalDateTime createdDate;
    private LocalDateTime updatedAt;
}
