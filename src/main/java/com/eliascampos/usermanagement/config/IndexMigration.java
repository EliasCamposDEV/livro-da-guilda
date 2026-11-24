package com.eliascampos.usermanagement.config;

import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Indices criados por migracao explicita e versionada.
 * Com auto-index-creation desligado, a criacao passa a fazer parte do deploy.
 */
@Component
public class IndexMigration implements ApplicationRunner {

    private static final String MIGRATIONS = "schema_migrations";
    private static final String VERSION = "001-indices";

    private final MongoTemplate mongoTemplate;

    public IndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Query applied = new Query(Criteria.where("_id").is(VERSION));
        if (mongoTemplate.findOne(applied, Document.class, MIGRATIONS) != null) {
            return;
        }

        createIndexes();

        try {
            mongoTemplate.insert(new Document("_id", VERSION).append("appliedAt", Instant.now()), MIGRATIONS);
        } catch (DuplicateKeyException ignored) {
            // Outra instancia aplicou a mesma versao durante a subida.
        }
    }

    private void createIndexes() {
        mongoTemplate.indexOps("usuarios").ensureIndex(
                new Index().on("email", Sort.Direction.ASC).unique().named("email"));
        mongoTemplate.indexOps("usuarios").ensureIndex(
                new Index().on("passwordResetTokenHash", Sort.Direction.ASC).unique().sparse()
                        .named("passwordResetTokenHash"));
        mongoTemplate.indexOps("usuarios").ensureIndex(
                new Index().on("role", Sort.Direction.ASC).named("role"));
        mongoTemplate.indexOps("usuarios").ensureIndex(
                new Index().on("active", Sort.Direction.ASC).named("active"));

        mongoTemplate.indexOps("auditoria").ensureIndex(
                new Index().on("timestamp", Sort.Direction.DESC).on("_id", Sort.Direction.DESC)
                        .named("audit_timestamp_id"));
        mongoTemplate.indexOps("auditoria").ensureIndex(
                new Index().on("actorEmail", Sort.Direction.ASC).named("actorEmail"));
        mongoTemplate.indexOps("auditoria").ensureIndex(
                new Index().on("action", Sort.Direction.ASC).named("action"));
        mongoTemplate.indexOps("auditoria").ensureIndex(
                new Index().on("targetUserId", Sort.Direction.ASC).named("targetUserId"));
    }
}
