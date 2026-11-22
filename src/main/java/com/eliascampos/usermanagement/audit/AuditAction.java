package com.eliascampos.usermanagement.audit;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Tipo de acao registrada na auditoria")
public enum AuditAction {

    @Schema(description = "Criacao de usuario")
    USER_CREATED,

    @Schema(description = "Atualizacao de dados do usuario")
    USER_UPDATED,

    @Schema(description = "Alteracao de status (ativo/inativo)")
    USER_STATUS_CHANGED,

    @Schema(description = "Exclusao de usuario")
    USER_DELETED,

    @Schema(description = "Troca de senha")
    PASSWORD_CHANGED,

    @Schema(description = "Solicitacao de redefinicao de senha")
    PASSWORD_RESET_REQUESTED,

    @Schema(description = "Redefinicao de senha concluida")
    PASSWORD_RESET_COMPLETED,

    @Schema(description = "Logout (rotacao de refresh token)")
    USER_LOGGED_OUT
}
