package com.eliascampos.usermanagement.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Janela fixa de tentativas de refresh por IP, em memoria e por instancia.
 */
@Component
public class RefreshRateLimiter {

    static final int MAX_ATTEMPTS = 30;
    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public void check(String key) {
        Attempts current = attempts.get(key);
        if (current != null && !current.expired() && current.count() >= MAX_ATTEMPTS) {
            throw new TooManyAttemptsException(WINDOW);
        }
    }

    public void record(String key) {
        if (attempts.size() >= MAX_TRACKED_KEYS) {
            attempts.entrySet().removeIf(e -> e.getValue().expired());
            if (attempts.size() >= MAX_TRACKED_KEYS) {
                attempts.clear();
            }
        }
        attempts.compute(key, (ignored, previous) ->
                previous == null || previous.expired()
                        ? new Attempts(Instant.now(), 1)
                        : new Attempts(previous.start(), previous.count() + 1));
    }

    private record Attempts(Instant start, int count) {
        boolean expired() {
            return start.plus(WINDOW).isBefore(Instant.now());
        }
    }
}