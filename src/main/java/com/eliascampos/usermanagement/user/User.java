package com.eliascampos.usermanagement.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.time.Instant;

@Document(collection = "usuarios")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    private String name;

    private String nameNormalized;

    private String email;

    private String passwordHash;

    private String passwordResetTokenHash;

    private Instant passwordResetExpiresAt;

    private UserRole role;

    private boolean active;

    // Versao do refresh token em vigor: cada refresh emitido carrega este numero e a rotacao o incrementa.
    private int tokenVersion;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    protected User() {
        // Construtor exigido pelo Spring Data MongoDB.
    }

    public User(String name, String email, UserRole role, String passwordHash) {
        this.name = name.trim();
        this.nameNormalized = normalizeName(name);
        this.email = email;
        this.role = role;
        this.passwordHash = passwordHash;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String name, String email, UserRole role) {
        this.name = name.trim();
        this.nameNormalized = normalizeName(name);
        this.email = email;
        this.role = role;
        this.updatedAt = Instant.now();
    }

    public void changeStatus(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
        if (!active) {
            // Usuario inativo nao pode manter um token de redefinicao pendente.
            clearPasswordReset();
        }
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
        clearPasswordReset();
    }

    public void startPasswordReset(String tokenHash, Instant expiresAt) {
        this.passwordResetTokenHash = tokenHash;
        this.passwordResetExpiresAt = expiresAt;
    }

    public void clearPasswordReset() {
        this.passwordResetTokenHash = null;
        this.passwordResetExpiresAt = null;
    }

    public void rotateTokenVersion() {
        this.tokenVersion++;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNameNormalized() {
        return nameNormalized;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPasswordResetTokenHash() {
        return passwordResetTokenHash;
    }

    public Instant getPasswordResetExpiresAt() {
        return passwordResetExpiresAt;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
