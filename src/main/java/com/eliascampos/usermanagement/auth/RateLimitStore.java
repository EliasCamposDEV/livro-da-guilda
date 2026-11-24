package com.eliascampos.usermanagement.auth;

import java.time.Duration;

/**
 * Contadores de janela fixa compartilhados pelos limiters.
 * A implementacao padrao e local; com Redis habilitado, o contador e compartilhado entre instancias.
 */
public interface RateLimitStore {

    record State(int count, long retryAfterSeconds) {
    }

    State get(String key, Duration window);

    State increment(String key, Duration window);

    void reset(String key);

    void clear();
}
