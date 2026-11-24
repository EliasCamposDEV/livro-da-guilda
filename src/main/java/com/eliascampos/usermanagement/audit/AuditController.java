package com.eliascampos.usermanagement.audit;

import com.eliascampos.usermanagement.audit.dto.AuditEventResponse;
import com.eliascampos.usermanagement.audit.dto.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Tag(name = "Auditoria", description = "Historico de acoes administrativas sobre usuarios")
@RestController
@RequestMapping("/api/v1/audit")
@SecurityRequirement(name = "bearerAuth")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Operation(summary = "Lista eventos de auditoria",
            description = "Somente ADMIN. Filtros por acao, autor, usuario afetado e periodo. "
                    + "Paginação por cursor: use nextCursor da resposta; sem contagem total.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina de eventos com nextCursor"),
            @ApiResponse(responseCode = "400", description = "Cursor ou limit invalido",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = com.eliascampos.usermanagement.exception.ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou invalido",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = com.eliascampos.usermanagement.exception.ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Token sem papel ADMIN",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = com.eliascampos.usermanagement.exception.ApiError.class)))
    })
    @GetMapping
    public ResponseEntity<CursorPage<AuditEventResponse>> findAll(
            @Parameter(description = "Filtra por tipo de acao")
            @RequestParam(required = false) AuditAction action,
            @Parameter(description = "Filtra por e-mail de quem executou a acao")
            @RequestParam(required = false) String actorEmail,
            @Parameter(description = "Filtra pelo id do usuario afetado")
            @RequestParam(required = false) String targetUserId,
            @Parameter(description = "Data inicial (ISO-8601)")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Data final (ISO-8601)")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Cursor opaco da pagina anterior")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Quantidade maxima de eventos (1 a 50)")
            @RequestParam(defaultValue = "20") int limit) {

        CursorPage<AuditEvent> page = auditService.findAll(
                action, actorEmail, targetUserId, from, to, cursor, limit);
        return ResponseEntity.ok(new CursorPage<>(
                page.content().stream().map(AuditEventResponse::from).toList(),
                page.nextCursor()));
    }
}
