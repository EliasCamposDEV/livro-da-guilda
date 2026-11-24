package com.eliascampos.usermanagement.audit;

import com.eliascampos.usermanagement.audit.dto.CursorPage;
import com.eliascampos.usermanagement.exception.BusinessRuleException;
import com.eliascampos.usermanagement.user.User;
import com.eliascampos.usermanagement.user.UserRepository;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEventRepository;
    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public AuditService(AuditEventRepository auditEventRepository, MongoTemplate mongoTemplate,
                        UserRepository userRepository,
                        @org.springframework.beans.factory.annotation.Autowired(required = false)
                        io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.auditEventRepository = auditEventRepository;
        this.mongoTemplate = mongoTemplate;
        this.userRepository = userRepository;
        this.meterRegistry = meterRegistry;
    }

    public void record(AuditAction action, User target, Map<String, Object> details) {
        record(action, target.getId(), target.getEmail(), details);
    }

    public void record(AuditAction action, String targetUserId, String targetUserEmail,
                       Map<String, Object> details) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorEmail;
        String actorRole;
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            actorEmail = "system";
            actorRole = "SYSTEM";
        } else {
            String principalName = authentication.getName();
            User user = userRepository.findById(principalName).orElse(null);
            if (user != null) {
                actorEmail = user.getEmail();
                actorRole = user.getRole().name();
            } else {
                actorEmail = principalName;
                actorRole = "UNKNOWN";
                if (authentication.getAuthorities() != null) {
                    for (var authority : authentication.getAuthorities()) {
                        String name = authority.getAuthority();
                        if (name != null && name.startsWith("ROLE_")) {
                            actorRole = name.substring("ROLE_".length());
                            break;
                        }
                    }
                }
            }
        }

        save(action, actorEmail, actorRole, targetUserId, targetUserEmail, details);
    }

    /**
     * Registra uma acao de fluxo publico, em que nao ha usuario autenticado.
     * Nao usa o SecurityContext para nao gravar "anonymousUser" no historico.
     */
    public void recordSystem(AuditAction action, User target, Map<String, Object> details) {
        save(action, "system", "SYSTEM", target.getId(), target.getEmail(), details);
    }

    public void recordForActor(AuditAction action, String actorEmail, String targetUserId,
                               String targetUserEmail, Map<String, Object> details) {
        save(action, actorEmail, actorEmail.equals("system") ? "SYSTEM" : "UNKNOWN", targetUserId, targetUserEmail, details);
    }

    private void save(AuditAction action, String actorEmail, String actorRole,
                      String targetUserId, String targetUserEmail, Map<String, Object> details) {
        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            ip = request.getRemoteAddr();
        }

        AuditEvent event = new AuditEvent(actorEmail, actorRole, action, targetUserId,
                targetUserEmail, details, ip);
        try {
            auditEventRepository.save(event);
        } catch (RuntimeException ex) {
            log.error("Falha ao registrar auditoria {}: {}", action, ex.getMessage());
            if (meterRegistry != null) {
                meterRegistry.counter("audit.write.failures", "action", action.name()).increment();
            }
        }
    }

    public CursorPage<AuditEvent> findAll(AuditAction action, String actorEmail, String targetUserId,
                                          Instant from, Instant to, String cursor, int limit) {
        if (limit < 1 || limit > 50) {
            throw new BusinessRuleException("limit deve estar entre 1 e 50");
        }
        AuditCursor after = cursor == null || cursor.isBlank() ? null : AuditCursor.decode(cursor);

        List<Criteria> filters = new ArrayList<>();

        if (action != null) {
            filters.add(Criteria.where("action").is(action.name()));
        }
        if (actorEmail != null && !actorEmail.isBlank()) {
            filters.add(Criteria.where("actorEmail").is(actorEmail.trim().toLowerCase(java.util.Locale.ROOT)));
        }
        if (targetUserId != null && !targetUserId.isBlank()) {
            filters.add(Criteria.where("targetUserId").is(targetUserId.trim()));
        }
        if (from != null) {
            filters.add(Criteria.where("timestamp").gte(from));
        }
        if (to != null) {
            filters.add(Criteria.where("timestamp").lte(to));
        }
        if (after != null) {
            filters.add(new Criteria().orOperator(
                    Criteria.where("timestamp").lt(after.timestamp()),
                    new Criteria().andOperator(
                            Criteria.where("timestamp").is(after.timestamp()),
                            Criteria.where("_id").lt(new ObjectId(after.id())))));
        }

        Criteria criteria = filters.isEmpty()
                ? new Criteria()
                : new Criteria().andOperator(filters.toArray(Criteria[]::new));
        Query query = new Query(criteria)
                .with(Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("_id")))
                .limit(limit + 1);

        List<AuditEvent> found = mongoTemplate.find(query, AuditEvent.class);
        boolean hasMore = found.size() > limit;
        List<AuditEvent> content = hasMore ? found.subList(0, limit) : found;
        String nextCursor = hasMore && !content.isEmpty()
                ? AuditCursor.encode(content.get(content.size() - 1))
                : null;

        return new CursorPage<>(content, nextCursor);
    }
}
