package com.paytm.wallet.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class LiveLogService {

    private static final int MAX_LOGS = 200;
    private final ConcurrentLinkedDeque<Map<String, Object>> recentLogs = new ConcurrentLinkedDeque<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public void broadcast(String level, String message, String correlationId, Map<String, Object> extra) {
        Map<String, Object> entry = new ConcurrentHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("level", level);
        entry.put("message", message);
        if (correlationId != null) {
            entry.put("correlation_id", correlationId);
        }
        if (extra != null) {
            entry.putAll(extra);
        }

        recentLogs.addLast(entry);
        while (recentLogs.size() > MAX_LOGS) {
            recentLogs.pollFirst();
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(entry));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    public List<Map<String, Object>> getRecentLogs() {
        return new ArrayList<>(recentLogs);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(180_000L); // 3 min timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }
}
