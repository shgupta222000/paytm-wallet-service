package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public class TransferRequest {

    @NotNull(message = "from wallet ID is required")
    @JsonProperty("from")
    private UUID from;

    @NotNull(message = "to wallet ID is required")
    @JsonProperty("to")
    private UUID to;

    @NotNull(message = "amount_paise is required")
    @Positive(message = "amount_paise must be strictly positive")
    @JsonProperty("amount_paise")
    private Long amountPaise;

    @NotBlank(message = "idempotency_key is required")
    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    public TransferRequest() {
    }

    public TransferRequest(UUID from, UUID to, Long amountPaise, String idempotencyKey) {
        this.from = from;
        this.to = to;
        this.amountPaise = amountPaise;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getFrom() {
        return from;
    }

    public void setFrom(UUID from) {
        this.from = from;
    }

    public UUID getTo() {
        return to;
    }

    public void setTo(UUID to) {
        this.to = to;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public void setAmountPaise(Long amountPaise) {
        this.amountPaise = amountPaise;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
