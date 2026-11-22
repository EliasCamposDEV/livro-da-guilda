package com.eliascampos.usermanagement.auth;

import com.eliascampos.usermanagement.user.User;
import com.eliascampos.usermanagement.user.UserRepository;
import com.eliascampos.usermanagement.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.security.password-reset.delivery=none")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetUnavailableIntegrationTest {

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

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRepository.save(new User("Admin", ADMIN_EMAIL, UserRole.ADMIN,
                passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    @Test
    void shouldReturn503AndNotCreateTokenWhenDeliveryIsNone() throws Exception {
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", ADMIN_EMAIL))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_UNAVAILABLE"));

        User stored = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
        assertThat(stored.getPasswordResetTokenHash()).isNull();
        assertThat(stored.getPasswordResetExpiresAt()).isNull();
    }

    @Test
    void shouldStillBlockResetPasswordWhenTokenIsUnknown() throws Exception {
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "token-qualquer",
                                "newPassword", "novaSenha123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
    }
}
