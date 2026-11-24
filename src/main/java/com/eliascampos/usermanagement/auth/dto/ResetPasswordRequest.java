package com.eliascampos.usermanagement.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.eliascampos.usermanagement.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @Schema(description = "Token de redefinicao recebido pelo canal configurado",
                example = "b7Jx0hH9kPq3vY8sT2mN5cR1wA4dF6gL0zXeU7iO")
        @NotBlank(message = "O token e obrigatorio")
        @Size(max = 100, message = "O token deve ter no maximo 100 caracteres")
        String token,

        @Schema(description = "Nova senha, entre 8 e 72 caracteres", example = "novaSenha123")
        @NotBlank(message = "A nova senha e obrigatoria")
        @Size(min = 8, max = 72, message = "A nova senha deve ter entre 8 e 72 caracteres")
        @MaxBytes(72)
        String newPassword
) {
}
