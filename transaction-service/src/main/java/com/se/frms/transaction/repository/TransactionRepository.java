package com.se.frms.transaction.repository;

import com.se.frms.transaction.entity.TransactionMaster;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<TransactionMaster, UUID> {
}
