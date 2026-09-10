package com.paytm.wallet.controller;

import com.paytm.wallet.dto.CreateWalletRequest;
import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * POST /wallets - get-or-create a wallet for a user.
     * Concurrent calls for the same user yield exactly one wallet.
     */
    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreateWallet(@Valid @RequestBody CreateWalletRequest request) {
        WalletResponse response = walletService.getOrCreateWallet(request.getUserId(), request.getInitialBalancePaise());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /wallets/{id} - current balance of the wallet.
     */
    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable UUID id) {
        WalletResponse response = walletService.getWallet(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Helper audit endpoint to inspect total money currently in the system.
     * Used by evaluators and burst scripts to verify conservation.
     */
    @GetMapping("/system/conservation")
    public ResponseEntity<Map<String, Object>> getSystemConservation() {
        Long totalBalancePaise = walletService.getTotalSystemBalancePaise();
        return ResponseEntity.ok(Map.of(
                "total_balance_paise", totalBalancePaise,
                "total_balance_inr", totalBalancePaise / 100.0
        ));
    }
}
