package com.eliascampos.usermanagement.auth;

public class TooManyPasswordResetRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyPasswordResetRequestsException(long retryAfterSeconds) {
        super("Muitas solicitacoes de recuperacao de senha");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
