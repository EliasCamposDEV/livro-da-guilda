package com.eliascampos.usermanagement.audit;

import com.eliascampos.usermanagement.audit.dto.AuditEventResponse;
import com.eliascampos.usermanagement.user.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springdoc.core.annotations.ParameterObject;

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
            description = "Somente ADMIN. Permite filtrar por acao, autor, usuario afetado e periodo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina de eventos"),
            @ApiResponse(responseCode = "401", description = "Token ausente ou invalido",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = com.eliascampos.usermanagement.exception.ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Token sem papel ADMIN",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = com.eliascampos.usermanagement.exception.ApiError.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponse<AuditEventResponse>> findAll(
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
            @ParameterObject
            @PageableDefault(size = 20, sort = "timestamp", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<AuditEventResponse> page = auditService.findAll(action, actorEmail, targetUserId, from, to, pageable)
                .map(AuditEventResponse::from);
        return ResponseEntity.ok(PageResponse.from(page));
    }
}
