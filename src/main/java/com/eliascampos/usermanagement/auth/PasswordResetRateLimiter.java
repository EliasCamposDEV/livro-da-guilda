package com.eliascampos.usermanagement.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Janela fixa de solicitacoes de recuperacao de senha, em memoria e por instancia.
 * Conta toda solicitacao, inclusive para e-mail inexistente, para nao criar um
 * oraculo de existencia. Duas dimensoes: e-mail + IP e IP isolado.
 */
@Component
public class PasswordResetRateLimiter {

    static final int MAX_PER_EMAIL_AND_IP = 5;
    static final int MAX_PER_IP = 20;
    static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Attempts> attempts = new HashMap<>();

    public synchronized void acquire(String email, String ipAddress) {
        Instant now = Instant.now();
        evictExpiredIfNeeded(now);

        String emailKey = emailKey(email, ipAddress);
        String ipKey = ipKey(ipAddress);
        long retryAfter = Math.max(
                retryAfterOrZero(emailKey, MAX_PER_EMAIL_AND_IP, now),
                retryAfterOrZero(ipKey, MAX_PER_IP, now)
        );
        if (retryAfter > 0) {
            throw new TooManyPasswordResetRequestsException(retryAfter);
        }

        increment(emailKey, now);
        increment(ipKey, now);
    }

    /** Zera o estado; usado pelos testes para isolar cenarios. */
    public synchronized void clear() {
        attempts.clear();
    }

    private long retryAfterOrZero(String key, int max, Instant now) {
        Attempts current = attempts.get(key);
        if (current == null || current.expired(now) || current.count() < max) {
            return 0;
        }
        return Math.max(1, Duration.between(now, current.start().plus(WINDOW)).toSeconds());
    }

    private void increment(String key, Instant now) {
        Attempts current = attempts.get(key);
        if (current == null || current.expired(now)) {
            attempts.put(key, new Attempts(now, 1));
        } else {
            attempts.put(key, new Attempts(current.start(), current.count() + 1));
        }
    }

    private void evictExpiredIfNeeded(Instant now) {
        if (attempts.size() < MAX_TRACKED_KEYS) {
            return;
        }

        attempts.entrySet().removeIf(entry -> entry.getValue().expired(now));
        if (attempts.size() < MAX_TRACKED_KEYS) {
            return;
        }

        java.util.List<String> oldest = attempts.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.getValue().start()))
                .limit(Math.max(1, attempts.size() / 5))
                .map(java.util.Map.Entry::getKey)
                .toList();
        oldest.forEach(attempts::remove);
    }

    private String emailKey(String email, String ipAddress) {
        return "email:" + email + "@" + ipAddress;
    }

    private String ipKey(String ipAddress) {
        return "ip:" + ipAddress;
    }

    private record Attempts(Instant start, int count) {

        boolean expired(Instant now) {
            return start.plus(WINDOW).isBefore(now);
        }
    }
}
