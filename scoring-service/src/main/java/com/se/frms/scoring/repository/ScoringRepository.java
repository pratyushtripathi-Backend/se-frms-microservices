package com.se.frms.scoring.repository;

import com.se.frms.scoring.entity.Scoring;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoringRepository extends JpaRepository<Scoring, UUID> {
}
