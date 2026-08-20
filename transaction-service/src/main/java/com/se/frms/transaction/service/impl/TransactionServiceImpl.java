package com.se.frms.transaction.service.impl;

import com.se.frms.transaction.constant.TransactionStatus;
import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.dto.TransactionDetailsResponse;
import com.se.frms.transaction.dto.TransactionRequest;
import com.se.frms.transaction.dto.TransactionResponse;
import com.se.frms.transaction.entity.TransactionMaster;
import com.se.frms.transaction.exception.ResourceNotFoundException;
import com.se.frms.transaction.repository.TransactionRepository;
import com.se.frms.transaction.service.DuplicateFraudNotificationService;
import com.se.frms.transaction.service.TransactionEvaluationService;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private static final String SYSTEM_USER = "TRANSACTION_SERVICE";

    private final TransactionRepository transactionRepository;
    private final DuplicateFraudNotificationService duplicateFraudNotificationService;
    private final TransactionEvaluationService transactionEvaluationService;

    @Override
    public TransactionResponse process(TransactionRequest request) {
        long startedAt = System.nanoTime();
        log.info(
                "Processing transaction externalTransactionId={}, merchantId={}, userId={}",
                request.externalTransactionId(),
                request.merchantId(),
                request.userId()
        );
        TransactionMaster transaction = createTransaction(request);
        Optional<TransactionMaster> originalTransaction = transactionRepository
                .findFirstByExternalTransactionIdOrderByCreatedDateAsc(transaction.getExternalTransactionId());

        if (originalTransaction.isPresent()) {
            log.warn(
                    "Duplicate transaction detected externalTransactionId={}, originalTransactionId={}",
                    transaction.getExternalTransactionId(),
                    originalTransaction.get().getId()
            );
            markAsDuplicateFraud(transaction, originalTransaction.get());
        }
        transaction = transactionRepository.save(transaction);

        if (Boolean.TRUE.equals(transaction.getDuplicateTransaction())) {
            duplicateFraudNotificationService.notifyFraudEngine(
                    new FraudEvaluationRequest(transaction.getId(), transaction.getTransactionData())
            );
            log.warn(
                    "Duplicate fraud response returned transactionId={}, externalTransactionId={}, originalTransactionId={}, elapsedMs={}",
                    transaction.getId(),
                    transaction.getExternalTransactionId(),
                    transaction.getOriginalTransactionId(),
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

        transaction.setStatus(TransactionStatus.FRAUD_EVALUATION_IN_PROGRESS.name());
        transaction.setUpdatedAt(LocalDateTime.now());
        transaction = transactionRepository.save(transaction);
        transactionEvaluationService.evaluate(transaction.getId(), transaction.getTransactionData());
        log.info(
                "Transaction accepted for async fraud evaluation transactionId={}, externalTransactionId={}, elapsedMs={}",
                transaction.getId(),
                transaction.getExternalTransactionId(),
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
        enrichTransactionData(transaction, data);
        transaction.setDuplicateTransaction(false);
        transaction.setTransactionData(data);
        transaction.setRemarks(request.remarks());
        transaction.setStatus(TransactionStatus.RECEIVED.name());
        transaction.setCreatedBy(resolveCreatedBy(request.createdBy()));
        transaction.setCreatedDate(now);
        transaction.setUpdatedAt(now);
        return transaction;
    }

    private void enrichTransactionData(TransactionMaster transaction, Map<String, Object> data) {
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
    }

    private void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.putIfAbsent(key, value);
        }
    }

    private void markAsDuplicateFraud(TransactionMaster transaction, TransactionMaster original) {
        transaction.setStatus(TransactionStatus.DUPLICATE_FRAUD.name());
        transaction.setDuplicateTransaction(true);
        transaction.setOriginalTransactionId(original.getId());
        transaction.setRemarks("Duplicate idempotent transaction detected for externalTransactionId "
                + transaction.getExternalTransactionId());
        transaction.getTransactionData().put("duplicateTransaction", true);
        transaction.getTransactionData().put("duplicateReason", "IDEMPOTENT_TRANSACTION_RETRY");
        transaction.getTransactionData().put("originalTransactionId", original.getId().toString());
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
