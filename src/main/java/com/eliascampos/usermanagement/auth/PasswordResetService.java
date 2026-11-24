package com.eliascampos.usermanagement.auth;

import com.eliascampos.usermanagement.audit.AuditAction;
import com.eliascampos.usermanagement.audit.AuditService;
import com.eliascampos.usermanagement.user.User;
import com.eliascampos.usermanagement.user.UserRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int TOKEN_BYTES = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final PasswordResetRateLimiter rateLimiter;
    private final Optional<PasswordResetNotifier> notifier;
    private final Clock clock;
    private final Duration tokenTtl;

    public PasswordResetService(UserRepository userRepository,
                                MongoTemplate mongoTemplate,
                                PasswordEncoder passwordEncoder,
                                AuditService auditService,
                                PasswordResetRateLimiter rateLimiter,
                                Optional<PasswordResetNotifier> notifier,
                                Clock clock,
                                @Value("${app.security.password-reset.token-ttl:PT15M}") Duration tokenTtl) {
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
        this.notifier = notifier;
        this.clock = clock;
        this.tokenTtl = tokenTtl;
    }

    public void requestReset(String rawEmail, String ipAddress) {
        String email = normalizeEmail(rawEmail);
        rateLimiter.acquire(email, ipAddress);

        PasswordResetNotifier sender = notifier.orElseThrow(PasswordResetUnavailableException::new);

        Optional<User> found = userRepository.findByEmail(email);
        if (found.isEmpty() || !found.get().isActive()) {
            return;
        }

        User user = found.get();
        String token = generateToken();
        String tokenHash = hashToken(token);
        Instant expiresAt = clock.instant().plus(tokenTtl);
        user.startPasswordReset(tokenHash, expiresAt);
        userRepository.save(user);

        try {
            sender.send(user.getEmail(), token, expiresAt);
        } catch (RuntimeException ex) {
            // Limpeza atomica e condicionada ao hash recem-gerado, para nao
            // sobrescrever mudancas concorrentes (senha, tokenVersion, etc.).
            Query cleanup = Query.query(Criteria.where("passwordResetTokenHash").is(tokenHash));
            Update cleanupUpdate = new Update()
                    .unset("passwordResetTokenHash")
                    .unset("passwordResetExpiresAt");
            mongoTemplate.updateFirst(cleanup, cleanupUpdate, User.class);
            throw new PasswordResetUnavailableException();
        }

        auditService.recordSystem(AuditAction.PASSWORD_RESET_REQUESTED, user, Map.of(
                "email", user.getEmail()
        ));
    }

    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = hashToken(rawToken);
        User user = userRepository.findByPasswordResetTokenHash(tokenHash)
                .orElseThrow(InvalidPasswordResetTokenException::new);

        Instant now = clock.instant();
        if (!user.isActive() || user.getPasswordResetExpiresAt() == null
                || !user.getPasswordResetExpiresAt().isAfter(now)) {
            // Limpeza atomica e condicionada ao hash do token, para nao
            // sobrescrever mudancas concorrentes (senha, tokenVersion, etc.).
            Query cleanup = Query.query(Criteria.where("passwordResetTokenHash").is(tokenHash));
            Update cleanupUpdate = new Update()
                    .unset("passwordResetTokenHash")
                    .unset("passwordResetExpiresAt");
            mongoTemplate.updateFirst(cleanup, cleanupUpdate, User.class);
            throw new InvalidPasswordResetTokenException();
        }

        String newPasswordHash = passwordEncoder.encode(newPassword);
        Query query = Query.query(Criteria.where("passwordResetTokenHash").is(tokenHash)
                .and("passwordResetExpiresAt").gt(now)
                .and("active").is(true));
        Update update = new Update()
                .set("passwordHash", newPasswordHash)
                .set("updatedAt", now)
                .inc("tokenVersion", 1)
                .inc("version", 1)
                .unset("passwordResetTokenHash")
                .unset("passwordResetExpiresAt");
        UpdateResult result = mongoTemplate.updateFirst(query, update, User.class);
        if (result.getModifiedCount() == 0) {
            // Outra requisicao consumiu o token entre a leitura e a atualizacao.
            throw new InvalidPasswordResetTokenException();
        }

        auditService.recordSystem(AuditAction.PASSWORD_RESET_COMPLETED, user, Map.of(
                "email", user.getEmail()
        ));
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponivel", ex);
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
