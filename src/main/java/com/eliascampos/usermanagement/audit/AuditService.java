package com.eliascampos.usermanagement.audit;

import com.eliascampos.usermanagement.user.User;
import com.eliascampos.usermanagement.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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

    public Page<AuditEvent> findAll(AuditAction action, String actorEmail, String targetUserId,
                                   Instant from, Instant to, Pageable pageable) {
        java.util.Set<String> allowedSortFields = java.util.Set.of(
                "timestamp", "action", "actorEmail", "targetUserId", "targetUserEmail"
        );
        java.util.List<org.springframework.data.domain.Sort.Order> safeOrders = pageable.getSort().stream()
                .filter(order -> allowedSortFields.contains(order.getProperty()))
                .toList();
        org.springframework.data.domain.Sort safeSort = safeOrders.isEmpty()
                ? org.springframework.data.domain.Sort.unsorted()
                : org.springframework.data.domain.Sort.by(safeOrders);
        pageable = org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), safeSort
        );

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

        Criteria criteria = filters.isEmpty()
                ? new Criteria()
                : new Criteria().andOperator(filters.toArray(Criteria[]::new));

        long total = mongoTemplate.count(new Query(criteria), AuditEvent.class);
        List<AuditEvent> events = mongoTemplate.find(new Query(criteria).with(pageable), AuditEvent.class);

        return new PageImpl<>(events, pageable, total);
    }


}
