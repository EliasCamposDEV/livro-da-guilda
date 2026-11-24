package com.eliascampos.usermanagement.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Janela fixa de solicitacoes de recuperacao de senha no RateLimitStore.
 * Conta toda solicitacao, inclusive para e-mail inexistente, para nao criar um
 * oraculo de existencia. Duas dimensoes: e-mail + IP e IP isolado.
 */
@Component
public class PasswordResetRateLimiter {

    static final int MAX_PER_EMAIL_AND_IP = 5;
    static final int MAX_PER_IP = 20;
    static final int MAX_PER_EMAIL = 10;
    static final Duration WINDOW = Duration.ofHours(1);

    private final RateLimitStore store;

    @Autowired
    public PasswordResetRateLimiter(RateLimitStore store) {
        this.store = store;
    }

    public synchronized void acquire(String email, String ipAddress) {
        String emailKey = "reset:email:" + email + "@" + ipAddress;
        String ipKey = "reset:ip:" + ipAddress;
        String accountKey = "reset:account:" + email;
        long retryAfter = Math.max(
                Math.max(
                        retryAfterOrZero(emailKey, MAX_PER_EMAIL_AND_IP),
                        retryAfterOrZero(ipKey, MAX_PER_IP)
                ),
                retryAfterOrZero(accountKey, MAX_PER_EMAIL)
        );
        if (retryAfter > 0) {
            throw new TooManyPasswordResetRequestsException(retryAfter);
        }

        store.increment(emailKey, WINDOW);
        store.increment(ipKey, WINDOW);
        store.increment(accountKey, WINDOW);
    }

    /** Zera o estado; usado pelos testes para isolar cenarios. */
    public synchronized void clear() {
        store.clear();
    }

    private long retryAfterOrZero(String key, int max) {
        RateLimitStore.State state = store.get(key, WINDOW);
        if (state.count() < max) {
            return 0;
        }
        return Math.max(1, state.retryAfterSeconds());
    }
}
