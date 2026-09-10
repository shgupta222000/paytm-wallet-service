package com.paytm.wallet.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricService {

    private final Counter transfersSuccessCounter;
    private final Counter transfersDeclinedCounter;
    private final Counter idempotentReplaysCounter;
    private final Counter idempotentConflictsCounter;
    private final Counter transfersReversedCounter;
    private final AtomicLong totalBalanceGaugeValue = new AtomicLong(0);

    public MetricService(MeterRegistry registry) {
        this.transfersSuccessCounter = Counter.builder("wallet_transfers_total")
                .tag("status", "success")
                .description("Total number of successfully completed transfers")
                .register(registry);

        this.transfersDeclinedCounter = Counter.builder("wallet_transfers_total")
                .tag("status", "declined_insufficient_funds")
                .description("Total transfers declined due to insufficient balance")
                .register(registry);

        this.idempotentReplaysCounter = Counter.builder("wallet_idempotent_replays_total")
                .description("Total number of idempotent replayed responses")
                .register(registry);

        this.idempotentConflictsCounter = Counter.builder("wallet_idempotent_conflicts_total")
                .description("Total number of 409 idempotency conflicts")
                .register(registry);

        this.transfersReversedCounter = Counter.builder("wallet_transfers_reversed_total")
                .description("Total number of reversed transfers")
                .register(registry);

        Gauge.builder("wallet_total_balance_paise", totalBalanceGaugeValue, AtomicLong::get)
                .description("Sum total of all wallet balances in paise (conservation monitor)")
                .register(registry);
    }

    public void incrementTransferSuccess() {
        transfersSuccessCounter.increment();
    }

    public void incrementTransferDeclined() {
        transfersDeclinedCounter.increment();
    }

    public void incrementIdempotentReplay() {
        idempotentReplaysCounter.increment();
    }

    public void incrementIdempotentConflict() {
        idempotentConflictsCounter.increment();
    }

    public void incrementTransferReversed() {
        transfersReversedCounter.increment();
    }

    public void updateTotalBalance(long totalPaise) {
        totalBalanceGaugeValue.set(totalPaise);
    }
}
