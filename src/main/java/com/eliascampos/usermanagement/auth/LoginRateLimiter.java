package com.eliascampos.usermanagement.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Janela fixa de tentativas de login por chave (e-mail + IP) e por IP.
 * Os contadores ficam no RateLimitStore: locais por padrao, compartilhados no Redis.
 */
@Component
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_PER_IP = 30;

    private final RateLimitStore store;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(RateLimitStore store) {
        this.store = store;
    }

    public void check(String key) {
        if (store.get("login:" + key, WINDOW).count() >= MAX_ATTEMPTS) {
            throw new TooManyAttemptsException(WINDOW);
        }
    }

    public void recordFailure(String key) {
        store.increment("login:" + key, WINDOW);
        locks.computeIfAbsent(key, ignored -> new Object());
    }

    public void reset(String key) {
        store.reset("login:" + key);
        locks.remove(key);
    }

    public void checkIp(String ip) {
        if (store.get("login-ip:" + ip, WINDOW).count() >= MAX_PER_IP) {
            throw new TooManyAttemptsException(WINDOW);
        }
    }

    public void recordFailureIp(String ip) {
        store.increment("login-ip:" + ip, WINDOW);
    }

    /** Retorna um lock por chave para serializar check/record/reset e evitar corrida. */
    public Object lock(String key) {
        return locks.computeIfAbsent(key, ignored -> new Object());
    }

    /** Zera o estado; usado pelos testes para isolar cenarios. */
    public void clear() {
        store.clear();
        locks.clear();
    }
}
