package com.se.frms.transaction.dto;

import com.se.frms.transaction.entity.TransactionMaster;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One received transaction, exactly as it will be saved, published by the POST to the
 * Kafka topic frms.transactions (key = transaction id) and consumed by this same
 * service's TransactionIngestConsumer, which saves it and starts the fraud evaluation.
 */
public record TransactionReceivedEvent(
        UUID id,
        String externalTransactionId,
        String ipAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        String merchantId,
        String userId,
        String channel,
        String transactionType,
        String currency,
        BigDecimal amount,
        Boolean duplicateTransaction,
        UUID originalTransactionId,
        Map<String, Object> transactionData,
        String remarks,
        String status,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt,
        String correlationId
) {

    public static TransactionReceivedEvent from(TransactionMaster t, String correlationId) {
        return new TransactionReceivedEvent(
                t.getId(), t.getExternalTransactionId(), t.getIpAddress(), t.getLatitude(), t.getLongitude(),
                t.getMerchantId(), t.getUserId(), t.getChannel(), t.getTransactionType(), t.getCurrency(),
                t.getAmount(), t.getDuplicateTransaction(), t.getOriginalTransactionId(), t.getTransactionData(),
                t.getRemarks(), t.getStatus(), t.getCreatedBy(), t.getCreatedDate(), t.getUpdatedAt(), correlationId
        );
    }

    public TransactionMaster toEntity() {
        TransactionMaster t = new TransactionMaster();
        t.setId(id);
        t.setExternalTransactionId(externalTransactionId);
        t.setIpAddress(ipAddress);
        t.setLatitude(latitude);
        t.setLongitude(longitude);
        t.setMerchantId(merchantId);
        t.setUserId(userId);
        t.setChannel(channel);
        t.setTransactionType(transactionType);
        t.setCurrency(currency);
        t.setAmount(amount);
        t.setDuplicateTransaction(duplicateTransaction);
        t.setOriginalTransactionId(originalTransactionId);
        t.setTransactionData(transactionData);
        t.setRemarks(remarks);
        t.setStatus(status);
        t.setCreatedBy(createdBy);
        t.setCreatedDate(createdDate);
        t.setUpdatedAt(updatedAt);
        return t;
    }
}
