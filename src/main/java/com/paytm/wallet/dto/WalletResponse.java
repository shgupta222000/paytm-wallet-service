package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.paytm.wallet.model.Wallet;

import java.time.Instant;
import java.util.UUID;

public class WalletResponse {

    private UUID id;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("balance_paise")
    private Long balancePaise;

    @JsonProperty("created_at")
    private Instant createdAt;

    public WalletResponse() {
    }

    public WalletResponse(UUID id, String userId, Long balancePaise, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.balancePaise = balancePaise;
        this.createdAt = createdAt;
    }

    public static WalletResponse fromEntity(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUserId(),
                wallet.getBalancePaise(),
                wallet.getCreatedAt()
        );
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Long getBalancePaise() {
        return balancePaise;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
