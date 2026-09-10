package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private String error;
    private String message;

    @JsonProperty("correlation_id")
    private String correlationId;

    private Instant timestamp;

    public ErrorResponse() {
    }

    public ErrorResponse(String error, String message, String correlationId) {
        this.error = error;
        this.message = message;
        this.correlationId = correlationId;
        this.timestamp = Instant.now();
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
