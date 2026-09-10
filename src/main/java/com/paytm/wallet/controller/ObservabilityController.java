package com.paytm.wallet.controller;

import com.paytm.wallet.service.LiveLogService;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class ObservabilityController {

    private final LiveLogService liveLogService;

    public ObservabilityController(LiveLogService liveLogService) {
        this.liveLogService = liveLogService;
    }

    /**
     * Dedicated health check endpoint for Docker HEALTHCHECK and cloud orchestrators.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    /**
     * Recent JSON logs endpoint so reviewers can view structured logs live.
     */
    @GetMapping("/logs/recent")
    public ResponseEntity<List<Map<String, Object>>> getRecentLogs() {
        return ResponseEntity.ok(liveLogService.getRecentLogs());
    }

    /**
     * Server-Sent Events (SSE) stream for streaming logs live in browser or terminal.
     */
    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs() {
        return liveLogService.subscribe();
    }
}
