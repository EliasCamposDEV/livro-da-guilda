package com.eliascampos.usermanagement.auth;

import java.time.Instant;

/**
 * Canal de entrega do token de redefinicao. A implementacao de e-mail real
 * entra no futuro; em dev/test o token vai para o log.
 */
public interface PasswordResetNotifier {

    void send(String email, String token, Instant expiresAt);
}
