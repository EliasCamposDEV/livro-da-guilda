package com.eliascampos.usermanagement.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pagina por cursor: nao informa total de registros porque nao faz count.
 */
@Schema(description = "Pagina por cursor, sem contagem total")
public record CursorPage<T>(

        @Schema(description = "Itens da pagina")
        List<T> content,

        @Schema(description = "Cursor para a proxima pagina; nulo quando nao ha mais eventos")
        String nextCursor

) {
    public CursorPage {
        content = content == null ? List.of() : List.copyOf(content);
    }

    @Override
    public List<T> content() {
        return content == null ? List.of() : List.copyOf(content);
    }
}
