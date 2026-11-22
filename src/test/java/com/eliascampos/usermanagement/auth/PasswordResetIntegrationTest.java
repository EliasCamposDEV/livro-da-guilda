package com.eliascampos.usermanagement.auth;

import com.eliascampos.usermanagement.audit.AuditAction;
import com.eliascampos.usermanagement.audit.AuditEvent;
import com.eliascampos.usermanagement.audit.AuditEventRepository;
import com.eliascampos.usermanagement.user.User;
import com.eliascampos.usermanagement.user.UserRepository;
import com.eliascampos.usermanagement.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=de.flapdoodle.embed.mongo.spring.autoconfigure.EmbeddedMongoAutoConfiguration",
        "app.security.password-reset.delivery=none"
})
@AutoConfigureMockMvc
class PasswordResetIntegrationTest {

    private static final String EMAIL = "maria@email.com";
    private static final String CURRENT_PASSWORD = "senha12345";
    private static final String NEW_PASSWORD = "novaSenha123";
    private static final String ADMIN_EMAIL = "admin@email.com";
    private static final String ADMIN_PASSWORD = "admin12345";

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @TestConfiguration
    static class CaptureConfig {
        @Bean
        @Primary
        CapturingPasswordResetNotifier capturingPasswordResetNotifier() {
            return new CapturingPasswordResetNotifier();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private PasswordResetRateLimiter rateLimiter;

    @Autowired
    private com.eliascampos.usermanagement.auth.LoginRateLimiter loginRateLimiter;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CapturingPasswordResetNotifier notifier;

    private User user;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        auditEventRepository.deleteAll();
        rateLimiter.clear();
        loginRateLimiter.clear();
        notifier.clear();
        user = userRepository.save(new User("Maria Silva", EMAIL, UserRole.USER,
                passwordEncoder.encode(CURRENT_PASSWORD)));
    }

    @Test
    void shouldResetPasswordEndToEndWithoutLeakingToken() throws Exception {
        MvcResult forgot = postForgot("  MARIA@Email.com  ", "127.0.0.1")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andReturn();

        assertThat(forgot.getResponse().getContentAsString()).isEmpty();
        assertThat(notifier.all()).hasSize(1);
        CapturingPasswordResetNotifier.Sent sent = notifier.all().get(0);
        assertThat(sent.email()).isEqualTo(EMAIL);
        assertThat(sent.token()).isNotBlank();

        User stored = findByEmail(EMAIL);
        assertThat(stored.getPasswordResetTokenHash())
                .isNotNull()
                .hasSize(64)
                .isNotEqualTo(sent.token());
        assertThat(stored.getPasswordResetExpiresAt())
                .isAfter(Instant.now())
                .isBefore(Instant.now().plusSeconds(16 * 60));
        Document raw = rawUser(stored.getId());
        assertThat(raw.getString("passwordResetTokenHash")).isEqualTo(stored.getPasswordResetTokenHash());
        assertThat(raw.containsKey("passwordResetExpiresAt")).isTrue();

        postReset(sent.token(), NEW_PASSWORD)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        User after = findByEmail(EMAIL);
        assertThat(after.getPasswordResetTokenHash()).isNull();
        assertThat(after.getPasswordResetExpiresAt()).isNull();
        assertThat(after.getTokenVersion()).isEqualTo(1);
        Document rawAfter = rawUser(stored.getId());
        assertThat(rawAfter.containsKey("passwordResetTokenHash")).isFalse();
        assertThat(rawAfter.containsKey("passwordResetExpiresAt")).isFalse();

        postLogin(EMAIL, CURRENT_PASSWORD).andExpect(status().isUnauthorized());
        postLogin(EMAIL, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void shouldAnswer204ForUnknownAndInactiveEmailsWithoutCreatingTokens() throws Exception {
        postForgot("ninguem@email.com", "127.0.0.1").andExpect(status().isNoContent());
        assertThat(notifier.all()).isEmpty();
        assertThat(userRepository.existsByEmail("ninguem@email.com")).isFalse();

        String token = requestResetToken(EMAIL);
        User stored = findByEmail(EMAIL);
        stored.changeStatus(false);
        userRepository.save(stored);
        notifier.clear();

        postForgot(EMAIL, "127.0.0.1").andExpect(status().isNoContent());
        assertThat(notifier.all()).isEmpty();
        assertThat(findByEmail(EMAIL).getPasswordResetTokenHash()).isNull();

        postReset(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
    }

    @Test
    void shouldRejectInvalidExpiredAndReusedTokens() throws Exception {
        postReset("token-inexistente", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));

        String expiredToken = requestResetToken(EMAIL);
        User stored = findByEmail(EMAIL);
        stored.startPasswordReset(stored.getPasswordResetTokenHash(), Instant.now().minusSeconds(30));
        userRepository.save(stored);

        postReset(expiredToken, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));

        User afterExpired = findByEmail(EMAIL);
        assertThat(afterExpired.getPasswordResetTokenHash()).isNull();
        assertThat(afterExpired.getPasswordResetExpiresAt()).isNull();

        String token = requestResetToken(EMAIL);
        postReset(token, NEW_PASSWORD).andExpect(status().isNoContent());
        postReset(token, "outraSenha123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
    }

    @Test
    void shouldKeepTokenWhenNewPasswordIsInvalid() throws Exception {
        String token = requestResetToken(EMAIL);
        String hashBefore = findByEmail(EMAIL).getPasswordResetTokenHash();

        postReset(token, "1234567")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.newPassword").isNotEmpty());

        assertThat(findByEmail(EMAIL).getPasswordResetTokenHash()).isEqualTo(hashBefore);
        postReset(token, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void shouldInvalidatePreviousTokenWhenRequestedAgain() throws Exception {
        String first = requestResetToken(EMAIL);
        String second = requestResetToken(EMAIL);

        assertThat(second).isNotEqualTo(first);
        postReset(first, NEW_PASSWORD)
                .andExpect(status().isBadRequest());
        postReset(second, NEW_PASSWORD)
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldAllowOnlyOneConcurrentReset() throws Exception {
        String token = requestResetToken(EMAIL);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> reset = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    passwordResetService.resetPassword(token, NEW_PASSWORD);
                    return true;
                } catch (InvalidPasswordResetTokenException ex) {
                    return false;
                }
            };
            Future<Boolean> first = executor.submit(reset);
            Future<Boolean> second = executor.submit(reset);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
        assertThat(findByEmail(EMAIL).getPasswordResetTokenHash()).isNull();
        postLogin(EMAIL, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void shouldRateLimitByEmailAndIpAndByIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            postForgot("desconhecido@email.com", "10.0.0.1").andExpect(status().isNoContent());
        }
        MvcResult blocked = postForgot("desconhecido@email.com", "10.0.0.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andReturn();
        assertThat(blocked.getResponse().getContentAsString().toLowerCase()).doesNotContain("login");

        rateLimiter.clear();

        for (int i = 0; i < 20; i++) {
            postForgot("user" + i + "@email.com", "10.0.0.2").andExpect(status().isNoContent());
        }
        postForgot("user20@email.com", "10.0.0.2")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void shouldRecordAuditWithSystemActorAndNoSecrets() throws Exception {
        String token = requestResetToken(EMAIL);
        postReset(token, NEW_PASSWORD).andExpect(status().isNoContent());

        List<AuditEvent> requested = auditEventRepository.findByAction(AuditAction.PASSWORD_RESET_REQUESTED);
        List<AuditEvent> completed = auditEventRepository.findByAction(AuditAction.PASSWORD_RESET_COMPLETED);
        assertThat(requested).hasSize(1);
        assertThat(completed).hasSize(1);

        AuditEvent requestEvent = requested.get(0);
        assertThat(requestEvent.getActorEmail()).isEqualTo("system");
        assertThat(requestEvent.getActorRole()).isEqualTo("SYSTEM");
        assertThat(requestEvent.getTargetUserId()).isEqualTo(user.getId());
        assertThat(requestEvent.getTargetUserEmail()).isEqualTo(EMAIL);
        assertThat(requestEvent.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(requestEvent.getDetails()).doesNotContainKeys("token", "passwordResetTokenHash");
        assertThat(requestEvent.getDetails().toString()).doesNotContain(token);

        AuditEvent completedEvent = completed.get(0);
        assertThat(completedEvent.getActorEmail()).isEqualTo("system");
        assertThat(completedEvent.getActorRole()).isEqualTo("SYSTEM");
        assertThat(completedEvent.getTargetUserId()).isEqualTo(user.getId());
        assertThat(completedEvent.getDetails().toString()).doesNotContain(token);
    }

    @Test
    void shouldClearTokenOnAuthenticatedPasswordChange() throws Exception {
        String token = requestResetToken(EMAIL);
        String access = login(EMAIL, CURRENT_PASSWORD);

        mockMvc.perform(put("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", CURRENT_PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        assertThat(findByEmail(EMAIL).getPasswordResetTokenHash()).isNull();
        postReset(token, "terceiraSenha123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
    }

    @Test
    void shouldClearTokenOnEmailChange() throws Exception {
        String token = requestResetToken(EMAIL);

        String adminAccess = createAdminAndLogin();
        String id = user.getId();
        mockMvc.perform(put("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Maria Silva",
                                "email", "maria.nova@email.com",
                                "role", "USER"))))
                .andExpect(status().isOk());

        assertThat(userRepository.findByEmail(EMAIL)).isEmpty();
        assertThat(userRepository.findByEmail("maria.nova@email.com").orElseThrow()
                .getPasswordResetTokenHash()).isNull();

        postReset(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
    }

    @Test
    void shouldClearTokenOnDeactivation() throws Exception {
        String token = requestResetToken(EMAIL);

        String adminAccess = createAdminAndLogin();
        String id = user.getId();
        mockMvc.perform(patch("/api/v1/users/{id}/status", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk());

        assertThat(findByEmail(EMAIL).getPasswordResetTokenHash()).isNull();
        postReset(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
    }

    @Test
    void shouldExposeRoutesInOpenApi() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("/auth/forgot-password");
        assertThat(body).contains("/auth/reset-password");
        assertThat(body).contains("PASSWORD_RESET_UNAVAILABLE");
        assertThat(body).contains("INVALID_OR_EXPIRED_TOKEN");
        assertThat(body).contains("muitasSolicitacoes");
    }

    // Helpers

    private org.springframework.test.web.servlet.ResultActions postForgot(String email, String ip) throws Exception {
        return mockMvc.perform(post("/auth/forgot-password")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email))));
    }

    private org.springframework.test.web.servlet.ResultActions postReset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "newPassword", newPassword))));
    }

    private String requestResetToken(String email) throws Exception {
        postForgot(email, "127.0.0.1").andExpect(status().isNoContent());
        List<CapturingPasswordResetNotifier.Sent> sent = notifier.all();
        return sent.get(sent.size() - 1).token();
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = postLogin(email, password).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private org.springframework.test.web.servlet.ResultActions postLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))));
    }

    private User findByEmail(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    private Document rawUser(String id) {
        return mongoTemplate.getCollection("usuarios")
                .find(eq("_id", new ObjectId(id))).first();
    }

    private String createAdminAndLogin() throws Exception {
        if (userRepository.findByEmail(ADMIN_EMAIL).isEmpty()) {
            userRepository.save(new User("Admin", ADMIN_EMAIL, UserRole.ADMIN,
                    passwordEncoder.encode(ADMIN_PASSWORD)));
        }
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    static final class CapturingPasswordResetNotifier implements PasswordResetNotifier {
        private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(String email, String token, Instant expiresAt) {
            sent.add(new Sent(email, token, expiresAt));
        }

        List<Sent> all() {
            synchronized (sent) {
                return List.copyOf(sent);
            }
        }

        void clear() {
            sent.clear();
        }

        record Sent(String email, String token, Instant expiresAt) {
        }
    }
}
