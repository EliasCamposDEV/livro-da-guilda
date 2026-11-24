package com.eliascampos.usermanagement.security;

import com.eliascampos.usermanagement.auth.LoginRateLimiter;
import com.eliascampos.usermanagement.user.User;
import com.eliascampos.usermanagement.user.UserRepository;
import com.eliascampos.usermanagement.user.UserRole;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Liga o modo somente leitura sem o perfil demo (que trocaria o Mongo por um banco real)
 * para validar a regra de ponta a ponta, com a cadeia de seguranca real.
 */
@SpringBootTest(properties = "app.demo.read-only=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoReadOnlyIntegrationTest {

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
    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
        userRepository.save(new User("Admin", ADMIN_EMAIL, UserRole.ADMIN,
                passwordEncoder.encode(ADMIN_PASSWORD)));
        loginRateLimiter.clear();
    }

    @Test
    void shouldKeepLoginAndReadAvailable() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users").param("role", "ADMIN")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRedirectRootToSwagger() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/swagger-ui.html"));
    }

    @Test
    void shouldRejectWriteWithReadOnlyCode() throws Exception {
        String token = login();

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Maria Silva",
                                "email", "maria@email.com",
                                "password", "senha12345",
                                "role", "USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(DemoReadOnlyFilter.CODE));

        assertEquals(1, userRepository.count());
    }

    @Test
    void shouldRejectPasswordChange() throws Exception {
        String token = login();

        mockMvc.perform(put("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", ADMIN_PASSWORD,
                                "newPassword", "novaSenha123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(DemoReadOnlyFilter.CODE));
    }

    @Test
    void shouldRejectPasswordResetEndpoints() throws Exception {
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", ADMIN_EMAIL))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(DemoReadOnlyFilter.CODE));

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "token-qualquer",
                                "newPassword", "novaSenha123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(DemoReadOnlyFilter.CODE));
    }

    @Test
    void shouldKeepRefreshAndLogoutAvailable() throws Exception {
        String loginBody = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = objectMapper.readTree(loginBody).get("accessToken").asText();
        String refreshToken = objectMapper.readTree(loginBody).get("refreshToken").asText();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
