package com.eliascampos.usermanagement.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Janela fixa de tentativas de login por chave (e-mail + IP) e por IP.
 * Os contadores ficam no RateLimitStore: locais por padrao, compartilhados no Redis.
 * Locks usam stripes fixas: limitam a memoria e serializam logins da mesma conta.
 */
@Component
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_PER_IP = 30;
    private static final int LOCK_STRIPES = 256;

    private final RateLimitStore store;
    private final Object[] locks = new Object[LOCK_STRIPES];

    @Autowired
    public LoginRateLimiter(RateLimitStore store) {
        this.store = store;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    public void check(String key) {
        if (store.get("login:" + key, WINDOW).count() >= MAX_ATTEMPTS) {
            throw new TooManyAttemptsException(WINDOW);
        }
    }

    public void recordFailure(String key) {
        store.increment("login:" + key, WINDOW);
    }

    public void reset(String key) {
        store.reset("login:" + key);
    }

    public void checkIp(String ip) {
        if (store.get("login-ip:" + ip, WINDOW).count() >= MAX_PER_IP) {
            throw new TooManyAttemptsException(WINDOW);
        }
    }

    public void recordFailureIp(String ip) {
        store.increment("login-ip:" + ip, WINDOW);
    }

    /** Retorna um lock por conta para serializar check/record/reset e evitar corrida. */
    public Object lock(String key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }

    /** Zera o estado; usado pelos testes para isolar cenarios. */
    public void clear() {
        store.clear();
    }
}
