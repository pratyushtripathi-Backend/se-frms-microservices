package com.se.frms.decision.repository;

import com.se.frms.decision.entity.Decision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {
}
