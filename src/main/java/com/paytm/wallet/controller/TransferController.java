package com.paytm.wallet.controller;

import com.paytm.wallet.dto.ReverseTransferRequest;
import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * POST /transfers - moves money from one wallet to another.
     * Body carries from, to, amount_paise, and client-supplied idempotency_key.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> executeTransfer(@Valid @RequestBody TransferRequest request) {
        TransferResponse response = transferService.executeTransfer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /transfers/{id} - get transfer status.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable UUID id) {
        TransferResponse response = transferService.getTransfer(id);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /transfers/{id}/reverse - R3 live follow-up probe.
     * Moves exact amount back from recipient to sender idempotently.
     */
    @PostMapping("/{id}/reverse")
    public ResponseEntity<TransferResponse> reverseTransfer(
            @PathVariable UUID id,
            @Valid @RequestBody ReverseTransferRequest request) {
        TransferResponse response = transferService.reverseTransfer(id, request.getIdempotencyKey());
        return ResponseEntity.ok(response);
    }
}
