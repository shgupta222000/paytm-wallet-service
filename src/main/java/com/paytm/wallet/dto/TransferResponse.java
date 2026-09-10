package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paytm.wallet.model.Transfer;
import com.paytm.wallet.model.TransferStatus;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransferResponse {

    private UUID id;

    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    @JsonProperty("from")
    private UUID from;

    @JsonProperty("to")
    private UUID to;

    @JsonProperty("amount_paise")
    private Long amountPaise;

    @JsonProperty("status")
    private TransferStatus status;

    @JsonProperty("decline_reason")
    private String declineReason;

    @JsonProperty("created_at")
    private Instant createdAt;

    public TransferResponse() {
    }

    public TransferResponse(UUID id, String idempotencyKey, UUID from, UUID to, Long amountPaise,
                            TransferStatus status, String declineReason, Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.from = from;
        this.to = to;
        this.amountPaise = amountPaise;
        this.status = status;
        this.declineReason = declineReason;
        this.createdAt = createdAt;
    }

    public static TransferResponse fromEntity(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getIdempotencyKey(),
                transfer.getFromWalletId(),
                transfer.getToWalletId(),
                transfer.getAmountPaise(),
                transfer.getStatus(),
                transfer.getDeclineReason(),
                transfer.getCreatedAt()
        );
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getFrom() {
        return from;
    }

    public UUID getTo() {
        return to;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
