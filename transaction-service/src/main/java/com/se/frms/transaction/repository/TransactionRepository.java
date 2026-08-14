package com.se.frms.transaction.repository;

import com.se.frms.transaction.entity.TransactionMaster;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransactionRepository extends JpaRepository<TransactionMaster, UUID>, JpaSpecificationExecutor<TransactionMaster> {

    Optional<TransactionMaster> findFirstByExternalTransactionIdOrderByCreatedDateAsc(String externalTransactionId);
}
