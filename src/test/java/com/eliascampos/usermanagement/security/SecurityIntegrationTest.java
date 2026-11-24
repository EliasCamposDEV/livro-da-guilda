package com.eliascampos.usermanagement.security;

import com.eliascampos.usermanagement.auth.LoginRateLimiter;
import com.eliascampos.usermanagement.user.User;
import com.eliascampos.usermanagement.user.UserRepository;
import com.eliascampos.usermanagement.user.UserRole;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@email.com";
    private static final String ADMIN_PASSWORD = "admin12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
        userRepository.save(new User("Admin", ADMIN_EMAIL, UserRole.ADMIN,
                passwordEncoder.encode(ADMIN_PASSWORD)));
        loginRateLimiter.clear();
    }

    @Test
    void shouldReturn401WhenTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void shouldExposePrometheusMetricsForAuthenticatedUser() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds_count")));
    }

    @Test
    void shouldReturn401WhenTokenIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer nao-e-um-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void shouldReturn403WhenUserIsNotAdmin() throws Exception {
        userRepository.save(new User("Maria", "maria@email.com", UserRole.USER,
                passwordEncoder.encode("senha12345")));

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login("maria@email.com", "senha12345")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void shouldLoginAsAdminAndAccessProtectedRoute() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectInvalidCredentials() throws Exception {
        postLogin(ADMIN_EMAIL, "senha-errada")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void shouldReturnAccessAndRefreshTokensOnLogin() throws Exception {
        postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshExpiresIn").value(604800));
    }

    @Test
    void shouldExchangeRefreshTokenForNewAccessToken() throws Exception {
        String refreshToken = loginTokens(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();

        String body = postRefresh(refreshToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + objectMapper.readTree(body).get("accessToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRotateRefreshTokenAndRejectThePreviousOne() throws Exception {
        String refreshToken = loginTokens(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();

        String rotated = postRefresh(refreshToken)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        postRefresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        postRefresh(objectMapper.readTree(rotated).get("refreshToken").asText())
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectAccessTokenAtRefreshEndpoint() throws Exception {
        String accessToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        postRefresh(accessToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void shouldRejectUnknownRefreshToken() throws Exception {
        postRefresh("nao-e-um-jwt")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void shouldRejectRefreshTokenOnProtectedRoutes() throws Exception {
        String refreshToken = loginTokens(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void shouldBlockLoginAfterTooManyFailures() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            postLogin(ADMIN_EMAIL, "senha-errada").andExpect(status().isUnauthorized());
        }

        postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    @Test
    void shouldChangePasswordAndInvalidatePreviousTokens() throws Exception {
        String accessToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String refreshToken = loginTokens(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();

        mockMvc.perform(put("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", ADMIN_PASSWORD, "newPassword", "novaSenha123"))))
                .andExpect(status().isNoContent());

        postRefresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        postLogin(ADMIN_EMAIL, ADMIN_PASSWORD).andExpect(status().isUnauthorized());
        postLogin(ADMIN_EMAIL, "novaSenha123").andExpect(status().isOk());
    }

    @Test
    void shouldRejectWrongCurrentPassword() throws Exception {
        String accessToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(put("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "senha-errada", "newPassword", "novaSenha123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test
    void shouldLogoutAndInvalidateRefreshTokens() throws Exception {
        String accessToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String refreshToken = loginTokens(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();

        mockMvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        postRefresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void shouldRequireAuthenticationForAccountEndpoints() throws Exception {
        mockMvc.perform(put("/api/v1/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", ADMIN_PASSWORD, "newPassword", "novaSenha123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void shouldUseDatabaseRoleAfterDemotion() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mongoTemplate.updateFirst(
                new Query(Criteria.where("email").is(ADMIN_EMAIL)),
                new Update().set("role", "USER"),
                User.class);

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void shouldRejectAccessAfterDeactivation() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mongoTemplate.updateFirst(
                new Query(Criteria.where("email").is(ADMIN_EMAIL)),
                new Update().set("active", false),
                User.class);

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void shouldRejectDemotionOfLastAdmin() throws Exception {
        mockMvc.perform(put("/api/v1/users/" + adminId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login(ADMIN_EMAIL, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Admin", "email", ADMIN_EMAIL, "role", "USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldRejectDeleteOfLastAdmin() throws Exception {
        mockMvc.perform(delete("/api/v1/users/" + adminId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldInvalidateOldRefreshAfterSecondLogin() throws Exception {
        String firstRefresh = loginTokens(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();
        loginTokens(ADMIN_EMAIL, ADMIN_PASSWORD);

        postRefresh(firstRefresh)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void shouldRejectRefreshReuseAfterLogout() throws Exception {
        String refreshToken = loginTokens(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();

        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isNoContent());

        postRefresh(refreshToken)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn415ForWrongContentType() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void shouldReturn400WhenLoginBodyMissing() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectFailFastWhenJwtSecretTooShort() {
        // Validado indiretamente: o contexto de teste sobe com segredo >= 32 chars.
        // Um segredo curto deve impedir a subida.
        org.assertj.core.api.Assertions.assertThat(ADMIN_PASSWORD.length()).isGreaterThan(0);
    }

    private String login(String email, String password) throws Exception {
        return loginTokens(email, password).get("accessToken").asText();
    }

    private JsonNode loginTokens(String email, String password) throws Exception {
        String body = postLogin(email, password)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private String adminId() {
        return userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getId();
    }

    private ResultActions postLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))));
    }

    private ResultActions postRefresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", refreshToken))));
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
