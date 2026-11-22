package com.eliascampos.usermanagement.auth;

public class InvalidPasswordResetTokenException extends RuntimeException {

    public InvalidPasswordResetTokenException() {
        super("Token de redefinicao invalido, expirado ou ja usado");
    }
}
