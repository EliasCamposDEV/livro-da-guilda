package com.eliascampos.usermanagement.audit;

import com.eliascampos.usermanagement.exception.BusinessRuleException;
import org.bson.types.ObjectId;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Cursor opaco composto pelo timestamp e pelo id do ultimo evento.
 * O id desempata eventos com o mesmo timestamp (o driver arredonda a milissegundos).
 */
record AuditCursor(Instant timestamp, String id) {

    static String encode(AuditEvent event) {
        String raw = event.getTimestamp().toEpochMilli() + "|" + event.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static AuditCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator < 1) {
                throw new IllegalArgumentException("separador ausente");
            }
            String id = raw.substring(separator + 1);
            if (!ObjectId.isValid(id)) {
                throw new IllegalArgumentException("id invalido");
            }
            return new AuditCursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))), id);
        } catch (RuntimeException ex) {
            throw new BusinessRuleException("Cursor invalido");
        }
    }
}
