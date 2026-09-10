package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public class ReverseTransferRequest {

    @NotBlank(message = "idempotency_key is required for reversal")
    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    public ReverseTransferRequest() {
    }

    public ReverseTransferRequest(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
