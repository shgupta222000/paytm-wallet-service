package com.paytm.wallet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.model.IdempotencyRecord;
import com.paytm.wallet.model.IdempotencyStatus;
import com.paytm.wallet.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private final MetricService metricService;
    private final TransactionTemplate requiresNewTemplate;

    public IdempotencyService(IdempotencyRecordRepository idempotencyRecordRepository,
                              ObjectMapper objectMapper,
                              MetricService metricService,
                              PlatformTransactionManager transactionManager) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
        this.metricService = metricService;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public enum ReserveOutcome {
        LEADER,
        REPLAY,
        IN_PROGRESS
    }

    public static class Reservation {
        private final ReserveOutcome outcome;
        private final TransferResponse cachedResponse;

        public Reservation(ReserveOutcome outcome, TransferResponse cachedResponse) {
            this.outcome = outcome;
            this.cachedResponse = cachedResponse;
        }

        public ReserveOutcome getOutcome() {
            return outcome;
        }

        public TransferResponse getCachedResponse() {
            return cachedResponse;
        }
    }

    /**
     * Atomically attempts to reserve the idempotency key in its own isolated transaction (REQUIRES_NEW).
     * Uses atomic SQL INSERT so concurrent collisions immediately trigger primary key constraints,
     * ensuring exactly ONE winner becomes LEADER.
     */
    public Reservation tryReserve(String key, String payloadHash) {
        Optional<IdempotencyRecord> opt = idempotencyRecordRepository.findById(key);
        if (opt.isPresent()) {
            IdempotencyRecord rec = opt.get();
            if (!rec.getRequestHash().equals(payloadHash)) {
                metricService.incrementIdempotentConflict();
                throw new IdempotencyConflictException("Idempotency key reused with different transfer parameters");
            }
            if (rec.getStatus() == IdempotencyStatus.COMPLETED && rec.getResponseBody() != null) {
                metricService.incrementIdempotentReplay();
                try {
                    TransferResponse res = objectMapper.readValue(rec.getResponseBody(), TransferResponse.class);
                    return new Reservation(ReserveOutcome.REPLAY, res);
                } catch (JsonProcessingException e) {
                    log.error("Failed to parse cached response body for key: {}", key, e);
                }
            }
            return new Reservation(ReserveOutcome.IN_PROGRESS, null);
        }

        try {
            return requiresNewTemplate.execute(status -> {
                idempotencyRecordRepository.insertKey(key, payloadHash, IdempotencyStatus.PROCESSING.name());
                return new Reservation(ReserveOutcome.LEADER, null);
            });
        } catch (Exception e) {
            log.info("idempotent_concurrent_insert_collision key={}", key);
            return new Reservation(ReserveOutcome.IN_PROGRESS, null);
        }
    }

    /**
     * Waits for an in-flight leader transaction to complete, then returns the cached response.
     */
    public TransferResponse waitForResult(String key, String payloadHash, int maxWaitMillis) {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            Optional<IdempotencyRecord> opt = idempotencyRecordRepository.findById(key);
            if (opt.isPresent()) {
                IdempotencyRecord rec = opt.get();
                if (!rec.getRequestHash().equals(payloadHash)) {
                    metricService.incrementIdempotentConflict();
                    throw new IdempotencyConflictException("Idempotency key reused with different transfer parameters");
                }
                if (rec.getStatus() == IdempotencyStatus.COMPLETED && rec.getResponseBody() != null) {
                    metricService.incrementIdempotentReplay();
                    try {
                        return objectMapper.readValue(rec.getResponseBody(), TransferResponse.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to parse cached response for key: {}", key, e);
                    }
                }
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("Timeout waiting for concurrent idempotency transaction: " + key);
    }

    public void markCompleted(String key, int statusCode, TransferResponse response) {
        try {
            requiresNewTemplate.executeWithoutResult(status -> {
                Optional<IdempotencyRecord> opt = idempotencyRecordRepository.findById(key);
                if (opt.isPresent()) {
                    IdempotencyRecord record = opt.get();
                    record.setStatus(IdempotencyStatus.COMPLETED);
                    record.setResponseStatus(statusCode);
                    try {
                        record.setResponseBody(objectMapper.writeValueAsString(response));
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to serialize response body for key: {}", key, e);
                    }
                    idempotencyRecordRepository.saveAndFlush(record);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to persist idempotency completion body for key: {}", key, e);
        }
    }
}
