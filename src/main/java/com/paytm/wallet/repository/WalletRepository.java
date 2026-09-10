package com.paytm.wallet.repository;

import com.paytm.wallet.model.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByUserId(String userId);

    // Pessimistic write lock for single wallet
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);

    // Pessimistic write lock for multiple wallets (used for P2P transfers)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id IN :ids")
    List<Wallet> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);

    // Used for validating the money conservation invariant (sum of all balances)
    @Query("SELECT COALESCE(SUM(w.balancePaise), 0) FROM Wallet w")
    Long sumTotalBalance();
}
