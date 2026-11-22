package com.eliascampos.usermanagement.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(
        @Schema(description = "E-mail da conta que esqueceu a senha", example = "maria.silva@email.com")
        @NotBlank(message = "O e-mail e obrigatorio")
        @Email(message = "Informe um e-mail valido")
        @Size(max = 160, message = "O e-mail deve ter no maximo 160 caracteres")
        String email
) {
    public ForgotPasswordRequest {
        // Normaliza antes da validacao para aceitar espacos acidentais no inicio/fim.
        email = email == null ? null : email.trim();
    }
}
