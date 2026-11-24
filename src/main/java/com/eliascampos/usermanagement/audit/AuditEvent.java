package com.eliascampos.usermanagement.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "auditoria")
public class AuditEvent {

    @Id
    private String id;

    private Instant timestamp;

    private String actorEmail;

    private String actorRole;

    private AuditAction action;

    private String targetUserId;

    private String targetUserEmail;

    private Map<String, Object> details;

    private String ipAddress;

    protected AuditEvent() {
        // Construtor exigido pelo Spring Data MongoDB.
    }

    public AuditEvent(String actorEmail, String actorRole, AuditAction action,
                      String targetUserId, String targetUserEmail,
                      Map<String, Object> details, String ipAddress) {
        this.timestamp = Instant.now();
        this.actorEmail = actorEmail;
        this.actorRole = actorRole;
        this.action = action;
        this.targetUserId = targetUserId;
        this.targetUserEmail = targetUserEmail;
        this.details = details == null ? null : new java.util.HashMap<>(details);
        this.ipAddress = ipAddress;
    }

    public String getId() {
        return id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public String getActorRole() {
        return actorRole;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    public String getTargetUserEmail() {
        return targetUserEmail;
    }

    public Map<String, Object> getDetails() {
        return details == null ? null : new java.util.HashMap<>(details);
    }

    public String getIpAddress() {
        return ipAddress;
    }
}
