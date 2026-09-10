package com.paytm.wallet.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    // negative for debit, positive for credit
    @Column(name = "amount_delta_paise", nullable = false)
    private Long amountDeltaPaise;

    @Column(name = "balance_after_paise", nullable = false)
    private Long balanceAfterPaise;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LedgerEntry() {
    }

    public LedgerEntry(UUID transferId, UUID walletId, Long amountDeltaPaise, Long balanceAfterPaise) {
        this.transferId = transferId;
        this.walletId = walletId;
        this.amountDeltaPaise = amountDeltaPaise;
        this.balanceAfterPaise = balanceAfterPaise;
    }

    @PrePersist
    public void onPrePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public Long getAmountDeltaPaise() {
        return amountDeltaPaise;
    }

    public Long getBalanceAfterPaise() {
        return balanceAfterPaise;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
