package com.se.frms.transaction.service.impl;

import com.se.frms.transaction.constant.TransactionStatus;
import com.se.frms.transaction.dto.TransactionDetailsResponse;
import com.se.frms.transaction.dto.TransactionReceivedEvent;
import com.se.frms.transaction.dto.TransactionRequest;
import com.se.frms.transaction.dto.TransactionResponse;
import com.se.frms.transaction.entity.TransactionMaster;
import com.se.frms.transaction.exception.ResourceNotFoundException;
import com.se.frms.transaction.repository.TransactionRepository;
import com.se.frms.transaction.service.DuplicateTransactionGuard;
import com.se.frms.transaction.service.TransactionPublisher;
import com.se.frms.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.se.frms.transaction.filter.CorrelationIdFilter;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private static final String SYSTEM_USER = "TRANSACTION_SERVICE";

    private final TransactionRepository transactionRepository;
    private final DuplicateTransactionGuard duplicateTransactionGuard;
    private final TransactionPublisher transactionPublisher;

    /**
     * Accepts a transaction: duplicate check in Redis, publish to Kafka
     * (frms.transactions), reply. No database work and no fraud evaluation on the
     * request thread any more - TransactionIngestConsumer saves the transaction (batch
     * INSERT) and then runs the same fraud evaluation as before.
     *
     * Same request, same response, same statuses as before (FRAUD_EVALUATION_IN_PROGRESS,
     * or DUPLICATE_FRAUD for a retried externalTransactionId).
     */
    @Override
    public TransactionResponse process(TransactionRequest request) {
        long startedAt = System.nanoTime();
        log.debug(
                "Processing transaction externalTransactionId={}, merchantId={}, userId={}",
                request.externalTransactionId(),
                request.merchantId(),
                request.userId()
        );
        TransactionMaster transaction = createTransaction(request);
        transaction.setId(UUID.randomUUID());

        long duplicateCheckStartedAt = System.nanoTime();
        Optional<UUID> originalTransactionId =
                duplicateTransactionGuard.claim(transaction.getExternalTransactionId(), transaction.getId());
        long duplicateCheckMs = elapsedMillis(duplicateCheckStartedAt);

        if (originalTransactionId.isPresent()) {
            log.warn(
                    "Duplicate transaction detected externalTransactionId={}, originalTransactionId={}",
                    transaction.getExternalTransactionId(),
                    originalTransactionId.get()
            );
            markAsDuplicateFraud(transaction, originalTransactionId.get());
        } else {
            transaction.setStatus(TransactionStatus.FRAUD_EVALUATION_IN_PROGRESS.name());
            transaction.setUpdatedAt(LocalDateTime.now());
        }

        // @Async no longer involved, but the correlation ID still travels with the
        // transaction (on the Kafka event) to the consumer and on to fraud-engine.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);

        long publishStartedAt = System.nanoTime();
        try {
            transactionPublisher.publish(TransactionReceivedEvent.from(transaction, correlationId));
        } catch (RuntimeException ex) {
            if (originalTransactionId.isEmpty()) {
                // Not accepted: let a retry of this request be treated as new, not as a duplicate.
                duplicateTransactionGuard.release(transaction.getExternalTransactionId(), transaction.getId());
            }
            log.error("Transaction could not be published externalTransactionId={}",
                    transaction.getExternalTransactionId(), ex);
            throw ex;
        }
        long publishMs = elapsedMillis(publishStartedAt);

        if (Boolean.TRUE.equals(transaction.getDuplicateTransaction())) {
            log.warn(
                    "Duplicate fraud response returned transactionId={}, externalTransactionId={}, originalTransactionId={}, duplicateCheckMs={}, publishMs={}, elapsedMs={}",
                    transaction.getId(),
                    transaction.getExternalTransactionId(),
                    transaction.getOriginalTransactionId(),
                    duplicateCheckMs,
                    publishMs,
                    elapsedMillis(startedAt)
            );
            return new TransactionResponse(
                    transaction.getId(),
                    transaction.getStatus(),
                    TransactionStatus.DUPLICATE_FRAUD.name(),
                    null,
                    transaction.getRemarks()
            );
        }

        log.info(
                "Transaction accepted for fraud evaluation transactionId={}, externalTransactionId={}, correlationId={}, duplicateCheckMs={}, publishMs={}, elapsedMs={}",
                transaction.getId(),
                transaction.getExternalTransactionId(),
                correlationId,
                duplicateCheckMs,
                publishMs,
                elapsedMillis(startedAt)
        );

        return new TransactionResponse(
                transaction.getId(),
                transaction.getStatus(),
                TransactionStatus.FRAUD_EVALUATION_IN_PROGRESS.name(),
                null,
                "Transaction accepted. Fraud evaluation is running in background."
        );
    }

    @Override
    public Page<TransactionDetailsResponse> getAll(
            Pageable pageable,
            String status,
            String merchantId,
            String userId,
            String channel,
            String transactionType,
            String currency,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        log.info(
                "Fetching transactions page={}, size={}, status={}, merchantId={}, userId={}, channel={}, transactionType={}, currency={}, fromDate={}, toDate={}",
                pageable.getPageNumber(),
                pageable.getPageSize(),
                status,
                merchantId,
                userId,
                channel,
                transactionType,
                currency,
                fromDate,
                toDate
        );
        return transactionRepository.findAll(
                buildTransactionSpecification(status, merchantId, userId, channel, transactionType, currency, fromDate, toDate),
                pageable
        ).map(this::mapToDetailsResponse);
    }

    @Override
    public TransactionDetailsResponse getById(UUID transactionId) {
        log.info("Fetching transaction by transactionId={}", transactionId);
        return transactionRepository.findById(transactionId)
                .map(this::mapToDetailsResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
    }

    private TransactionMaster createTransaction(TransactionRequest request) {
        LocalDateTime now = LocalDateTime.now();
        TransactionMaster transaction = new TransactionMaster();
        Map<String, Object> data = new HashMap<>(request.transactionData());

        transaction.setExternalTransactionId(resolveString(request.externalTransactionId(), data, "externalTransactionId", "external_transaction_id", "transactionReferenceId", "transaction_reference_id"));
        transaction.setIpAddress(resolveString(request.ipAddress(), data, "ipAddress", "ip_address"));
        transaction.setLatitude(resolveBigDecimal(request.latitude(), data, "latitude"));
        transaction.setLongitude(resolveBigDecimal(request.longitude(), data, "longitude"));
        transaction.setMerchantId(resolveString(request.merchantId(), data, "merchantId", "merchant_id"));
        transaction.setUserId(resolveString(request.userId(), data, "userId", "user_id"));
        transaction.setChannel(resolveString(request.channel(), data, "channel"));
        transaction.setTransactionType(resolveString(request.transactionType(), data, "transactionType", "transaction_type"));
        transaction.setCurrency(resolveString(request.currency(), data, "currency"));
        transaction.setAmount(resolveBigDecimal(request.amount(), data, "amount"));
        enrichTransactionData(transaction, data, now);
        transaction.setDuplicateTransaction(false);
        transaction.setTransactionData(data);
        transaction.setRemarks(request.remarks());
        transaction.setStatus(TransactionStatus.RECEIVED.name());
        transaction.setCreatedBy(resolveCreatedBy(request.createdBy()));
        transaction.setCreatedDate(now);
        transaction.setUpdatedAt(now);
        return transaction;
    }

    private void enrichTransactionData(TransactionMaster transaction, Map<String, Object> data, LocalDateTime createdDate) {
        putIfPresent(data, "externalTransactionId", transaction.getExternalTransactionId());
        putIfPresent(data, "ipAddress", transaction.getIpAddress());
        putIfPresent(data, "latitude", transaction.getLatitude());
        putIfPresent(data, "longitude", transaction.getLongitude());
        putIfPresent(data, "merchantId", transaction.getMerchantId());
        putIfPresent(data, "userId", transaction.getUserId());
        putIfPresent(data, "channel", transaction.getChannel());
        putIfPresent(data, "transactionType", transaction.getTransactionType());
        putIfPresent(data, "currency", transaction.getCurrency());
        putIfPresent(data, "amount", transaction.getAmount());
        // The transaction's actual create date/time (same value saved as
        // createdDate below) - stored as an ISO-8601 string so it survives
        // the Kafka JSON round-trip through FraudEvent.transactionData()
        // cleanly, same as every other field here. This lets notification-service
        // show the real transaction time in admin alerts, not evaluation/send time.
        putIfPresent(data, "transactionDate", createdDate.toString());
    }

    private void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.putIfAbsent(key, value);
        }
    }

    private void markAsDuplicateFraud(TransactionMaster transaction, UUID originalTransactionId) {
        transaction.setStatus(TransactionStatus.DUPLICATE_FRAUD.name());
        transaction.setDuplicateTransaction(true);
        transaction.setOriginalTransactionId(originalTransactionId);
        transaction.setRemarks("Duplicate idempotent transaction detected for externalTransactionId "
                + transaction.getExternalTransactionId());
        transaction.getTransactionData().put("duplicateTransaction", true);
        transaction.getTransactionData().put("duplicateReason", "IDEMPOTENT_TRANSACTION_RETRY");
        transaction.getTransactionData().put("originalTransactionId", originalTransactionId.toString());
        transaction.getTransactionData().put("fraudSignal", "DUPLICATE_EXTERNAL_TRANSACTION_ID");
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String resolveCreatedBy(String createdBy) {
        return StringUtils.hasText(createdBy) ? createdBy : SYSTEM_USER;
    }

    private String resolveString(String directValue, Map<String, Object> data, String... keys) {
        if (StringUtils.hasText(directValue)) {
            return directValue;
        }
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString();
            }
        }
        return null;
    }

    private BigDecimal resolveBigDecimal(BigDecimal directValue, Map<String, Object> data, String key) {
        if (directValue != null) {
            return directValue;
        }
        Object value = data.get(key);
        if (value == null || !StringUtils.hasText(value.toString())) {
            return null;
        }
        return new BigDecimal(value.toString());
    }

    private Specification<TransactionMaster> buildTransactionSpecification(
            String status,
            String merchantId,
            String userId,
            String channel,
            String transactionType,
            String currency,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        return (root, query, criteriaBuilder) -> {
            var predicate = criteriaBuilder.conjunction();

            if (StringUtils.hasText(status)) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(merchantId)) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.equal(root.get("merchantId"), merchantId));
            }
            if (StringUtils.hasText(userId)) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.equal(root.get("userId"), userId));
            }
            if (StringUtils.hasText(channel)) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.equal(root.get("channel"), channel));
            }
            if (StringUtils.hasText(transactionType)) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.equal(root.get("transactionType"), transactionType));
            }
            if (StringUtils.hasText(currency)) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.equal(root.get("currency"), currency));
            }
            if (fromDate != null) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.greaterThanOrEqualTo(
                        root.get("createdDate"),
                        fromDate.atStartOfDay()
                ));
            }
            if (toDate != null) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.lessThan(
                        root.get("createdDate"),
                        toDate.plusDays(1).atStartOfDay()
                ));
            }

            query.orderBy(criteriaBuilder.desc(root.get("createdDate")));
            return predicate;
        };
    }

    private TransactionDetailsResponse mapToDetailsResponse(TransactionMaster transaction) {
        return new TransactionDetailsResponse(
                transaction.getId(),
                transaction.getExternalTransactionId(),
                transaction.getIpAddress(),
                transaction.getLatitude(),
                transaction.getLongitude(),
                transaction.getMerchantId(),
                transaction.getUserId(),
                transaction.getChannel(),
                transaction.getTransactionType(),
                transaction.getCurrency(),
                transaction.getAmount(),
                transaction.getDuplicateTransaction(),
                transaction.getOriginalTransactionId(),
                transaction.getTransactionData(),
                transaction.getRemarks(),
                transaction.getStatus(),
                transaction.getCreatedBy(),
                transaction.getCreatedDate(),
                transaction.getUpdatedAt()
        );
    }
}
