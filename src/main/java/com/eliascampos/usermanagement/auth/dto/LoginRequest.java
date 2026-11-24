package com.eliascampos.usermanagement.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import com.eliascampos.usermanagement.validation.MaxBytes;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Schema(description = "E-mail de acesso. Na demo pública use demo@eliascampos.dev",
                example = "demo@eliascampos.dev")
        @NotBlank(message = "O e-mail e obrigatorio")
        @Email(message = "Informe um e-mail valido")
        @Size(max = 254, message = "O e-mail deve ter no maximo 254 caracteres")
        String email,

        @Schema(description = "Senha de acesso. Na demo pública use demo-readonly-2026",
                example = "demo-readonly-2026")
        @NotBlank(message = "A senha e obrigatoria")
        @Size(min = 1, max = 72, message = "A senha deve ter no maximo 72 caracteres")
        @MaxBytes(72)
        String password
) {
}
