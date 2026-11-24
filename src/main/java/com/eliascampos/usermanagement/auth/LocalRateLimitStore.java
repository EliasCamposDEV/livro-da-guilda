package com.eliascampos.usermanagement.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Contadores em memoria, usados quando nao ha Redis configurado. */
class LocalRateLimitStore implements RateLimitStore {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public State get(String key, Duration window) {
        Window current = windows.get(key);
        if (current == null || current.expired(window)) {
            return new State(0, 0);
        }
        return new State(current.count(), remainingSeconds(current, window));
    }

    @Override
    public State increment(String key, Duration window) {
        evictIfNeeded(window);
        Window next = windows.compute(key, (ignored, previous) ->
                previous == null || previous.expired(window)
                        ? new Window(Instant.now(), 1)
                        : new Window(previous.start(), previous.count() + 1));
        return new State(next.count(), remainingSeconds(next, window));
    }

    @Override
    public void reset(String key) {
        windows.remove(key);
    }

    @Override
    public void clear() {
        windows.clear();
    }

    private void evictIfNeeded(Duration window) {
        if (windows.size() < MAX_TRACKED_KEYS) {
            return;
        }
        windows.entrySet().removeIf(entry -> entry.getValue().expired(window));
        if (windows.size() >= MAX_TRACKED_KEYS) {
            windows.clear();
        }
    }

    private long remainingSeconds(Window current, Duration window) {
        long remaining = window.toMillis() - Duration.between(current.start(), Instant.now()).toMillis();
        return Math.max(0, remaining / 1000);
    }

    private record Window(Instant start, int count) {

        boolean expired(Duration window) {
            return start.plus(window).isBefore(Instant.now());
        }
    }
}
