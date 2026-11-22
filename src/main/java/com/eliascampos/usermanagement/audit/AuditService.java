package com.eliascampos.usermanagement.audit;

import com.eliascampos.usermanagement.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final MongoTemplate mongoTemplate;

    public AuditService(AuditEventRepository auditEventRepository, MongoTemplate mongoTemplate) {
        this.auditEventRepository = auditEventRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public void record(AuditAction action, User target, Map<String, Object> details) {
        record(action, target.getId(), target.getEmail(), details);
    }

    public void record(AuditAction action, String targetUserId, String targetUserEmail,
                       Map<String, Object> details) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorEmail = (authentication != null && authentication.isAuthenticated())
                ? authentication.getName()
                : "system";
        String actorRole = (authentication != null && authentication.getAuthorities() != null)
                ? authentication.getAuthorities().toString()
                : "SYSTEM";

        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            ip = request.getRemoteAddr();
        }

        AuditEvent event = new AuditEvent(actorEmail, actorRole, action, targetUserId,
                targetUserEmail, details, ip);
        auditEventRepository.save(event);
    }

    public Page<AuditEvent> findAll(AuditAction action, String actorEmail, String targetUserId,
                                   Instant from, Instant to, Pageable pageable) {
        List<Criteria> filters = new ArrayList<>();

        if (action != null) {
            filters.add(Criteria.where("action").is(action.name()));
        }
        if (actorEmail != null && !actorEmail.isBlank()) {
            filters.add(Criteria.where("actorEmail").regex("^" + escapeRegex(actorEmail.trim()) + "$", "i"));
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

    private String escapeRegex(String value) {
        return value.replaceAll("([\\\\.\\\\*\\\\+\\\\?\\\\^\\\\$\\\\{\\\\}\\\\(\\\\)\\\\|\\\\[\\\\]\\\\\\\\])", "\\\\$1");
    }
}
