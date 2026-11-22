package com.eliascampos.usermanagement.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Canal de desenvolvimento: o token bruto aparece no log para permitir testar
 * o fluxo sem provedor de e-mail. Nunca deve ser ligado em producao.
 */
@Component
@ConditionalOnProperty(name = "app.security.password-reset.delivery", havingValue = "log")
public class LoggingPasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetNotifier.class);

    @Override
    public void send(String email, String token, Instant expiresAt) {
        log.info("PASSWORD_RESET_TOKEN email={} token={} expiresAt={}", email, token, expiresAt);
    }
}
