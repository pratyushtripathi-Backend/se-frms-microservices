package com.se.frms.transaction.repository;

import com.se.frms.transaction.entity.TransactionMaster;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TransactionRepository extends JpaRepository<TransactionMaster, UUID>, JpaSpecificationExecutor<TransactionMaster> {

    Optional<TransactionMaster> findFirstByExternalTransactionIdOrderByCreatedDateAsc(String externalTransactionId);

    /**
     * Earlier rows for these externalTransactionIds (one query per batch). Used by
     * TransactionIngestConsumer as a safety net behind the Redis duplicate check
     * (e.g. a retry that arrives after the Redis key has expired).
     */
    @Query("SELECT t.id, t.externalTransactionId, t.createdDate FROM TransactionMaster t "
            + "WHERE t.externalTransactionId IN :externalIds")
    List<Object[]> findIdsByExternalTransactionIds(@Param("externalIds") Collection<String> externalIds);

    /** Ids of these transactions that are already saved (Kafka redelivery after a restart). */
    @Query("SELECT t.id FROM TransactionMaster t WHERE t.id IN :ids")
    List<UUID> findExistingIds(@Param("ids") Collection<UUID> ids);

    /** Status of one transaction (to skip re-evaluating a redelivered, already evaluated one). */
    @Query("SELECT t.status FROM TransactionMaster t WHERE t.id = :id")
    Optional<String> findStatusById(@Param("id") UUID id);

    /**
     * Fraud result for one transaction as a single UPDATE (instead of findById + save).
     * Returns the number of rows updated (0 if the row does not exist).
     */
    @Modifying
    @Transactional
    @Query("UPDATE TransactionMaster t SET t.status = :status, t.remarks = :remarks, t.updatedAt = :updatedAt "
            + "WHERE t.id = :id")
    int updateEvaluationResult(@Param("id") UUID id,
                               @Param("status") String status,
                               @Param("remarks") String remarks,
                               @Param("updatedAt") LocalDateTime updatedAt);
}
