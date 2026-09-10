package com.paytm.wallet.service;

import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.exception.ResourceNotFoundException;
import com.paytm.wallet.model.Wallet;
import com.paytm.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final MetricService metricService;

    public WalletService(WalletRepository walletRepository, MetricService metricService) {
        this.walletRepository = walletRepository;
        this.metricService = metricService;
    }

    /**
     * Get or create a wallet for a user.
     * Concurrency safe: if two requests race to create a wallet for the same user,
     * the database UNIQUE constraint on user_id will catch the second insert,
     * and we fall back to fetching the committed record.
     */
    public WalletResponse getOrCreateWallet(String userId, Long initialBalancePaise) {
        Optional<Wallet> existing = walletRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return WalletResponse.fromEntity(existing.get());
        }

        try {
            Long balance = (initialBalancePaise != null) ? initialBalancePaise : 0L;
            Wallet wallet = new Wallet(userId, balance);
            Wallet saved = walletRepository.save(wallet);

            log.info("wallet_created wallet_id={} user_id={} balance_paise={}",
                    saved.getId(), saved.getUserId(), saved.getBalancePaise());

            // Update conservation balance gauge
            metricService.updateTotalBalance(walletRepository.sumTotalBalance());
            return WalletResponse.fromEntity(saved);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent race condition won by another thread
            log.info("wallet_get_or_create_race_resolved user_id={}", userId);
            Wallet fallback = walletRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("Unable to load wallet after conflict for user: " + userId));
            return WalletResponse.fromEntity(fallback);
        }
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID id) {
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + id));
        return WalletResponse.fromEntity(wallet);
    }

    @Transactional(readOnly = true)
    public Long getTotalSystemBalancePaise() {
        return walletRepository.sumTotalBalance();
    }
}
