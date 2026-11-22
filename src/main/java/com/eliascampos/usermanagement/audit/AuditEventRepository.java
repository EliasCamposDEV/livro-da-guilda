package com.eliascampos.usermanagement.audit;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AuditEventRepository extends MongoRepository<AuditEvent, String> {

    List<AuditEvent> findByAction(AuditAction action);

    List<AuditEvent> findByActorEmail(String actorEmail);

    List<AuditEvent> findByTargetUserId(String targetUserId);

    List<AuditEvent> findByTimestampBetween(Instant from, Instant to);
}
