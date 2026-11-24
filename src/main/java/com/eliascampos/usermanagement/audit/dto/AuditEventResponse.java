package com.eliascampos.usermanagement.audit.dto;

import com.eliascampos.usermanagement.audit.AuditAction;
import com.eliascampos.usermanagement.audit.AuditEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "Evento de auditoria")
public record AuditEventResponse(

        @Schema(description = "Id do evento", example = "65f1a2b3c4d5e6f7a8b9c0d1")
        String id,

        @Schema(description = "Momento do evento (ISO-8601)", example = "2026-11-22T12:00:00Z")
        Instant timestamp,

        @Schema(description = "E-mail de quem executou a acao", example = "admin@email.com")
        String actorEmail,

        @Schema(description = "Papel de quem executou a acao", example = "[ROLE_ADMIN]")
        String actorRole,

        @Schema(description = "Tipo de acao registrada")
        AuditAction action,

        @Schema(description = "Id do usuario afetado", example = "65f1a2b3c4d5e6f7a8b9c0d1")
        String targetUserId,

        @Schema(description = "E-mail do usuario afetado", example = "maria.silva@email.com")
        String targetUserEmail,

        @Schema(description = "Detalhes da acao (campos alterados)")
        Map<String, Object> details,

        @Schema(description = "IP de origem", example = "127.0.0.1")
        String ipAddress

) {
    public AuditEventResponse {
        details = details == null ? null : new java.util.HashMap<>(details);
    }

    @Override
    public Map<String, Object> details() {
        return details == null ? null : new java.util.HashMap<>(details);
    }

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getTimestamp(),
                event.getActorEmail(),
                event.getActorRole(),
                event.getAction(),
                event.getTargetUserId(),
                event.getTargetUserEmail(),
                event.getDetails(),
                event.getIpAddress()
        );
    }
}
