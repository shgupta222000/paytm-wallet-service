package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public class CreateWalletRequest {

    @NotBlank(message = "userId is required")
    @JsonProperty("user_id")
    private String userId;

    @PositiveOrZero(message = "initial_balance_paise cannot be negative")
    @JsonProperty("initial_balance_paise")
    private Long initialBalancePaise;

    public CreateWalletRequest() {
    }

    public CreateWalletRequest(String userId, Long initialBalancePaise) {
        this.userId = userId;
        this.initialBalancePaise = initialBalancePaise;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getInitialBalancePaise() {
        return initialBalancePaise;
    }

    public void setInitialBalancePaise(Long initialBalancePaise) {
        this.initialBalancePaise = initialBalancePaise;
    }
}
