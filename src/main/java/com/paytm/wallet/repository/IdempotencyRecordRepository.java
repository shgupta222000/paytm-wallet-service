package com.paytm.wallet.repository;

import com.paytm.wallet.model.IdempotencyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.key = :key")
    Optional<IdempotencyRecord> findByKeyForUpdate(@Param("key") String key);

    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (\"key\", request_hash, status, created_at, updated_at) VALUES (:key, :hash, :status, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", nativeQuery = true)
    int insertKey(@Param("key") String key, @Param("hash") String hash, @Param("status") String status);
}
