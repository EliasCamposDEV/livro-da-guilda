package com.eliascampos.usermanagement.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Janela fixa de tentativas de refresh por IP, no RateLimitStore. */
@Component
public class RefreshRateLimiter {

    static final int MAX_ATTEMPTS = 30;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimitStore store;

    @Autowired
    public RefreshRateLimiter(RateLimitStore store) {
        this.store = store;
    }

    public void check(String key) {
        if (store.get("refresh:" + key, WINDOW).count() >= MAX_ATTEMPTS) {
            throw new TooManyAttemptsException(WINDOW);
        }
    }

    public void record(String key) {
        store.increment("refresh:" + key, WINDOW);
    }
}
