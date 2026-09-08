package com.se.frms.rulecache.repository;

import com.se.frms.rulecache.entity.BlacklistCache;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlacklistCacheRepository extends JpaRepository<BlacklistCache, UUID> {

    Optional<BlacklistCache> findByBlacklistId(Integer blacklistId);

    List<BlacklistCache> findByStatusTrue();

    List<BlacklistCache> findByStatusTrueOrderByUpdatedAtDesc();
}
