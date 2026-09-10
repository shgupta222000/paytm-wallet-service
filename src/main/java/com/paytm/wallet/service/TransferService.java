package com.paytm.wallet.service;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.exception.InsufficientBalanceException;
import com.paytm.wallet.exception.ResourceNotFoundException;
import com.paytm.wallet.exception.TransferAlreadyReversedException;
import com.paytm.wallet.model.LedgerEntry;
import com.paytm.wallet.model.Transfer;
import com.paytm.wallet.model.TransferStatus;
import com.paytm.wallet.model.Wallet;
import com.paytm.wallet.repository.LedgerEntryRepository;
import com.paytm.wallet.repository.TransferRepository;
import com.paytm.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final IdempotencyService idempotencyService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MetricService metricService;
    private final TransactionTemplate transactionTemplate;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           IdempotencyService idempotencyService,
                           LedgerEntryRepository ledgerEntryRepository,
                           MetricService metricService,
                           PlatformTransactionManager transactionManager) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.idempotencyService = idempotencyService;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.metricService = metricService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    }

    /**
     * Executes a P2P transfer with strict conservation, no-overdraft,
     * sorted row locking (deadlock prevention), and idempotency.
     */
    public TransferResponse executeTransfer(TransferRequest request) {
        String key = request.getIdempotencyKey().trim();
        String payloadHash = computePayloadHash(request.getFrom(), request.getTo(), request.getAmountPaise());

        // 1. Try to reserve idempotency key or replay if already completed
        IdempotencyService.Reservation reservation = idempotencyService.tryReserve(key, payloadHash);
        if (reservation.getOutcome() == IdempotencyService.ReserveOutcome.REPLAY) {
            log.info("idempotent_replay_hit key={}", key);
            return reservation.getCachedResponse();
        }
        if (reservation.getOutcome() == IdempotencyService.ReserveOutcome.IN_PROGRESS) {
            log.info("idempotent_waiting_for_leader key={}", key);
            return idempotencyService.waitForResult(key, payloadHash, 5000);
        }

        // 2. Validate basic domain rules
        if (request.getFrom().equals(request.getTo())) {
            throw new IllegalArgumentException("Source and destination wallet cannot be identical");
        }

        // 3. Winner thread: execute transfer atomically
        return transactionTemplate.execute(status -> {
            UUID fromId = request.getFrom();
            UUID toId = request.getTo();
            UUID firstLockId = fromId.compareTo(toId) < 0 ? fromId : toId;
            UUID secondLockId = fromId.compareTo(toId) < 0 ? toId : fromId;

            Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                    .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + firstLockId));
            Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                    .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + secondLockId));

            Wallet fromWallet = firstLockId.equals(fromId) ? firstWallet : secondWallet;
            Wallet toWallet = firstLockId.equals(toId) ? firstWallet : secondWallet;

            // Overdraft verification
            long amountPaise = request.getAmountPaise();
            if (fromWallet.getBalancePaise() < amountPaise) {
                log.info("transfer_declined_insufficient_funds from={} balance={} requested={}",
                        fromId, fromWallet.getBalancePaise(), amountPaise);

                Transfer declinedTransfer = new Transfer(
                        key, fromId, toId, amountPaise, TransferStatus.DECLINED_INSUFFICIENT_FUNDS
                );
                declinedTransfer.setDeclineReason("Insufficient balance: available=" + fromWallet.getBalancePaise() + " paise");
                transferRepository.save(declinedTransfer);

                metricService.incrementTransferDeclined();
                idempotencyService.markCompleted(key, 422, TransferResponse.fromEntity(declinedTransfer));

                throw new InsufficientBalanceException("Insufficient balance in source wallet: available=" + fromWallet.getBalancePaise() + " paise");
            }

            // Atomic balance update (Conservation guaranteed: -amount and +amount in same transaction)
            fromWallet.setBalancePaise(fromWallet.getBalancePaise() - amountPaise);
            toWallet.setBalancePaise(toWallet.getBalancePaise() + amountPaise);

            walletRepository.save(fromWallet);
            walletRepository.save(toWallet);

            // Record transfer entity
            Transfer transfer = new Transfer(key, fromId, toId, amountPaise, TransferStatus.SUCCESS);
            Transfer savedTransfer = transferRepository.save(transfer);

            // Audit ledger (Double-entry ledger logging for conservation audits)
            ledgerEntryRepository.save(new LedgerEntry(savedTransfer.getId(), fromId, -amountPaise, fromWallet.getBalancePaise()));
            ledgerEntryRepository.save(new LedgerEntry(savedTransfer.getId(), toId, amountPaise, toWallet.getBalancePaise()));

            TransferResponse response = TransferResponse.fromEntity(savedTransfer);

            // Commit idempotency record as COMPLETED
            idempotencyService.markCompleted(key, 201, response);

            metricService.incrementTransferSuccess();
            log.info("transfer_success transfer_id={} from={} to={} amount_paise={} from_balance={} to_balance={}",
                    savedTransfer.getId(), fromId, toId, amountPaise, fromWallet.getBalancePaise(), toWallet.getBalancePaise());

            // TODO: In production, publish transfer event to Kafka / EventBridge for downstream reconciliation
            return response;
        });
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found with id: " + transferId));
        return TransferResponse.fromEntity(transfer);
    }

    /**
     * R3 Follow-Up: Reversal / refund transfer
     * Moves exact amount back from recipient to sender.
     * Uses independent idempotency key, sorted locking, and guards against double-reversal.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse reverseTransfer(UUID transferId, String idempotencyKey) {
        String key = idempotencyKey.trim();

        // Check if original transfer exists
        Transfer original = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found: " + transferId));

        if (original.getStatus() == TransferStatus.REVERSED) {
            throw new TransferAlreadyReversedException("Transfer has already been reversed");
        }

        if (original.getStatus() != TransferStatus.SUCCESS) {
            throw new IllegalArgumentException("Cannot reverse a non-successful transfer: status is " + original.getStatus());
        }

        // Original recipient becomes source; original sender becomes destination
        UUID refundFromId = original.getToWalletId();
        UUID refundToId = original.getFromWalletId();
        long amountPaise = original.getAmountPaise();

        // Canonical sorted locking
        UUID firstLockId = refundFromId.compareTo(refundToId) < 0 ? refundFromId : refundToId;
        UUID secondLockId = refundFromId.compareTo(refundToId) < 0 ? refundToId : refundFromId;

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + firstLockId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + secondLockId));

        Wallet refundFromWallet = firstLockId.equals(refundFromId) ? firstWallet : secondWallet;
        Wallet refundToWallet = firstLockId.equals(refundToId) ? firstWallet : secondWallet;

        // Check if recipient has spent the funds
        if (refundFromWallet.getBalancePaise() < amountPaise) {
            log.warn("reversal_declined_insufficient_funds wallet_id={} balance={} required={}",
                    refundFromId, refundFromWallet.getBalancePaise(), amountPaise);
            throw new InsufficientBalanceException("Reversal declined: recipient has insufficient balance (" + refundFromWallet.getBalancePaise() + " paise)");
        }

        // Apply reversal balance movements
        refundFromWallet.setBalancePaise(refundFromWallet.getBalancePaise() - amountPaise);
        refundToWallet.setBalancePaise(refundToWallet.getBalancePaise() + amountPaise);

        walletRepository.save(refundFromWallet);
        walletRepository.save(refundToWallet);

        original.setStatus(TransferStatus.REVERSED);
        transferRepository.save(original);

        // Record audit ledger for reversal
        ledgerEntryRepository.save(new LedgerEntry(original.getId(), refundFromId, -amountPaise, refundFromWallet.getBalancePaise()));
        ledgerEntryRepository.save(new LedgerEntry(original.getId(), refundToId, amountPaise, refundToWallet.getBalancePaise()));

        metricService.incrementTransferReversed();
        log.info("transfer_reversed transfer_id={} amount_paise={}", transferId, amountPaise);

        return TransferResponse.fromEntity(original);
    }

    private String computePayloadHash(UUID from, UUID to, Long amountPaise) {
        try {
            String raw = from.toString() + "|" + to.toString() + "|" + amountPaise;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
