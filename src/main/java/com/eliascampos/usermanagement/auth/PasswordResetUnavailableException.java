package com.eliascampos.usermanagement.auth;

public class PasswordResetUnavailableException extends RuntimeException {

    public PasswordResetUnavailableException() {
        super("A recuperacao de senha nao esta disponivel neste ambiente");
    }
}
