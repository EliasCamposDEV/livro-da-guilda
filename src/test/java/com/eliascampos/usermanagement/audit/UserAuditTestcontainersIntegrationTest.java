package com.eliascampos.usermanagement.audit;

import org.bson.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude=de.flapdoodle.embed.mongo.spring.autoconfigure.EmbeddedMongoAutoConfiguration")
@AutoConfigureMockMvc
class UserAuditTestcontainersIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
    }

    private static final String ADMIN_EMAIL = "admin@email.com";
    private static final String ADMIN_PASSWORD = "admin12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.eliascampos.usermanagement.user.UserRepository userRepository;

    @Autowired
    private com.eliascampos.usermanagement.audit.AuditEventRepository auditEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MongoTemplate mongoTemplate;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        auditEventRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.save(new com.eliascampos.usermanagement.user.User(
                "Admin Teste", ADMIN_EMAIL, com.eliascampos.usermanagement.user.UserRole.ADMIN,
                passwordEncoder.encode(ADMIN_PASSWORD)));
        adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    @Test
    void auditRecordsUserCreationAgainstRealMongo() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Usuario TC",
                                "email", "usuario.tc@email.com",
                                "role", "USER",
                                "password", "senha12345"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].action").value("USER_CREATED"))
                .andExpect(jsonPath("$.content[0].actorEmail").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.content[0].targetUserEmail").value("usuario.tc@email.com"));
    }

    @Test
    void shouldFilterAuditByActorEmail() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Outro", "email", "outro.tc@email.com",
                                "password", "senha12345", "role", "USER"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/audit").param("actorEmail", ADMIN_EMAIL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/v1/audit").param("actorEmail", "ninguem@email.com")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void shouldPageAuditByCursorWithoutCounting() throws Exception {
        createUser("cursor1@email.com");
        createUser("cursor2@email.com");
        createUser("cursor3@email.com");

        var firstResult = mockMvc.perform(get("/api/v1/audit").param("limit", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andReturn();

        String cursor = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                .get("nextCursor").asText();

        var secondResult = mockMvc.perform(get("/api/v1/audit")
                        .param("limit", "2").param("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andReturn();

        var first = objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("content");
        var second = objectMapper.readTree(secondResult.getResponse().getContentAsString()).get("content");
        assertThat(first.get(0).get("id").asText()).isNotEqualTo(second.get(0).get("id").asText());
    }

    @Test
    void indexesComeFromVersionedMigration() {
        assertThat(mongoTemplate.indexOps("usuarios").getIndexInfo())
                .extracting(IndexInfo::getName)
                .contains("email", "passwordResetTokenHash", "role", "active");
        assertThat(mongoTemplate.indexOps("auditoria").getIndexInfo())
                .extracting(IndexInfo::getName)
                .contains("audit_timestamp_id", "actorEmail", "action", "targetUserId");
        assertThat(mongoTemplate.findOne(
                new Query(Criteria.where("_id").is("001-indices")), Document.class, "schema_migrations"))
                .isNotNull();
    }

    @Test
    void shouldRejectInvalidCursorAndLimit() throws Exception {
        mockMvc.perform(get("/api/v1/audit").param("cursor", "???")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/v1/audit").param("limit", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private void createUser(String email) throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Cursor User", "email", email,
                                "role", "USER", "password", "senha12345"))))
                .andExpect(status().isCreated());
    }

    private String login(String email, String password) throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
