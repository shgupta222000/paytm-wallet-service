package com.paytm.wallet;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.InsufficientBalanceException;
import com.paytm.wallet.exception.TransferAlreadyReversedException;
import com.paytm.wallet.model.TransferStatus;
import com.paytm.wallet.repository.WalletRepository;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class ConcurrencyAndInvariantsTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private WalletRepository walletRepository;

    @BeforeEach
    void setUp() {
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("Invariant 4: Concurrent get-or-create for same user yields exactly one wallet")
    void testConcurrentGetOrCreate() throws InterruptedException {
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<UUID> walletIds = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    WalletResponse response = walletService.getOrCreateWallet("user_concurrent_same", 1000L);
                    walletIds.add(response.getId());
                } catch (Exception e) {
                    fail("Unexpected failure in get-or-create: " + e.getMessage());
                }
            });
        }

        // Fire all threads simultaneously
        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(threads, walletIds.size());
        UUID firstId = walletIds.peek();
        assertNotNull(firstId);
        for (UUID id : walletIds) {
            assertEquals(firstId, id, "All concurrent get-or-create calls must return the identical wallet ID");
        }
    }

    @Test
    @DisplayName("Invariant 3: Idempotent retry storm applies transfer exactly once without double-debit")
    void testIdempotentRetryStorm() throws InterruptedException {
        WalletResponse walletA = walletService.getOrCreateWallet("user_retry_A", 10000L);
        WalletResponse walletB = walletService.getOrCreateWallet("user_retry_B", 0L);

        int attempts = 30;
        String sharedKey = "shared-idempotency-key-" + UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<TransferResponse> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    TransferRequest req = new TransferRequest(walletA.getId(), walletB.getId(), 2000L, sharedKey);
                    TransferResponse res = transferService.executeTransfer(req);
                    results.add(res);
                } catch (Exception e) {
                    fail("Unexpected error in retry storm: " + e.getMessage());
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(attempts, results.size());
        UUID transferId = results.peek().getId();
        for (TransferResponse res : results) {
            assertEquals(transferId, res.getId());
            assertEquals(TransferStatus.SUCCESS, res.getStatus());
        }

        // Balances must reflect exactly one debit of 2,000 paise
        WalletResponse refreshedA = walletService.getWallet(walletA.getId());
        WalletResponse refreshedB = walletService.getWallet(walletB.getId());
        assertEquals(8000L, refreshedA.getBalancePaise());
        assertEquals(2000L, refreshedB.getBalancePaise());
    }

    @Test
    @DisplayName("Invariant 3: Reusing same idempotency_key with different payload triggers 409 Conflict")
    void testIdempotencyConflictWithDifferentBody() {
        WalletResponse walletA = walletService.getOrCreateWallet("user_conflict_A", 10000L);
        WalletResponse walletB = walletService.getOrCreateWallet("user_conflict_B", 0L);

        String key = "key-conflict-" + UUID.randomUUID();
        TransferRequest req1 = new TransferRequest(walletA.getId(), walletB.getId(), 1000L, key);
        TransferResponse res1 = transferService.executeTransfer(req1);
        assertEquals(TransferStatus.SUCCESS, res1.getStatus());

        // Same key, different amount (2,000 instead of 1,000)
        TransferRequest req2 = new TransferRequest(walletA.getId(), walletB.getId(), 2000L, key);
        assertThrows(IdempotencyConflictException.class, () -> transferService.executeTransfer(req2));
    }

    @Test
    @DisplayName("Invariant 2: No overdraft - transfer fails cleanly when balance is insufficient")
    void testNoOverdraft() {
        WalletResponse walletA = walletService.getOrCreateWallet("user_overdraft_A", 1000L);
        WalletResponse walletB = walletService.getOrCreateWallet("user_overdraft_B", 0L);

        TransferRequest req = new TransferRequest(walletA.getId(), walletB.getId(), 5000L, "key-overdraft-" + UUID.randomUUID());
        assertThrows(InsufficientBalanceException.class, () -> transferService.executeTransfer(req));

        WalletResponse refreshedA = walletService.getWallet(walletA.getId());
        assertEquals(1000L, refreshedA.getBalancePaise());
    }

    @Test
    @DisplayName("Invariant 1: Conservation under heavy contention - sum of all balances stays constant")
    void testConservationUnderContention() throws Exception {
        int numWallets = 4;
        long initialBalancePerWallet = 10000L;
        long expectedTotalBalance = numWallets * initialBalancePerWallet;

        List<UUID> walletIds = new ArrayList<>();
        for (int i = 0; i < numWallets; i++) {
            WalletResponse w = walletService.getOrCreateWallet("contention_user_" + i, initialBalancePerWallet);
            walletIds.add(w.getId());
        }

        int totalTransfers = 60;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Callable<Void>> tasks = new ArrayList<>();
        Random random = new Random(42);

        for (int i = 0; i < totalTransfers; i++) {
            int fromIdx = random.nextInt(numWallets);
            int toIdx = random.nextInt(numWallets);
            while (toIdx == fromIdx) {
                toIdx = random.nextInt(numWallets);
            }

            UUID fromId = walletIds.get(fromIdx);
            UUID toId = walletIds.get(toIdx);
            long amount = 500L + random.nextInt(1500);
            String key = "key-contention-" + i + "-" + UUID.randomUUID();

            tasks.add(() -> {
                try {
                    TransferRequest req = new TransferRequest(fromId, toId, amount, key);
                    transferService.executeTransfer(req);
                } catch (InsufficientBalanceException ignored) {
                    // Normal behavior when source wallet temporarily lacks funds
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get();
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Invariant Check: Sum of all balances MUST equal pre-test sum exactly!
        long finalTotal = 0L;
        for (UUID id : walletIds) {
            WalletResponse w = walletService.getWallet(id);
            assertTrue(w.getBalancePaise() >= 0L, "Wallet balance must never be negative!");
            finalTotal += w.getBalancePaise();
        }

        assertEquals(expectedTotalBalance, finalTotal, "Total money across all wallets must remain strictly conserved!");
    }

    @Test
    @DisplayName("R3 Live Follow-up: Reversal transfer restores original balances")
    void testReversalTransfer() {
        WalletResponse walletA = walletService.getOrCreateWallet("user_rev_A", 5000L);
        WalletResponse walletB = walletService.getOrCreateWallet("user_rev_B", 1000L);

        TransferRequest req = new TransferRequest(walletA.getId(), walletB.getId(), 2000L, "key-original-" + UUID.randomUUID());
        TransferResponse transfer = transferService.executeTransfer(req);
        assertEquals(TransferStatus.SUCCESS, transfer.getStatus());

        // Reverse transfer
        TransferResponse reversed = transferService.reverseTransfer(transfer.getId(), "key-reverse-" + UUID.randomUUID());
        assertEquals(TransferStatus.REVERSED, reversed.getStatus());

        // Balances should be back to initial values
        WalletResponse finalA = walletService.getWallet(walletA.getId());
        WalletResponse finalB = walletService.getWallet(walletB.getId());
        assertEquals(5000L, finalA.getBalancePaise());
        assertEquals(1000L, finalB.getBalancePaise());

        // Attempting to reverse again throws exception
        assertThrows(TransferAlreadyReversedException.class,
                () -> transferService.reverseTransfer(transfer.getId(), "key-reverse-duplicate"));
    }
}
