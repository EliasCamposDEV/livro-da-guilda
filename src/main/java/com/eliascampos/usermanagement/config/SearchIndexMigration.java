package com.eliascampos.usermanagement.config;

import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

/**
 * Segunda versao das migracoes de indice: normaliza nomes antigos e cria os
 * indices usados pela busca por prefixo e pela ordenacao da listagem.
 * Idempotente, por isso pode rodar em cada subida sem duplicar trabalho util.
 */
@Component
public class SearchIndexMigration implements ApplicationRunner {

    private static final String MIGRATIONS = "schema_migrations";
    private static final String VERSION = "002-busca-ordem";

    private final MongoTemplate mongoTemplate;

    public SearchIndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mongoTemplate.findById(VERSION, Document.class, MIGRATIONS) != null) {
            return;
        }
        backfillNames();
        createIndexes();
        try {
            mongoTemplate.insert(new Document("_id", VERSION).append("appliedAt", Instant.now()), MIGRATIONS);
        } catch (DuplicateKeyException ignored) {
            // Versao ja aplicada por outra instancia.
        }
    }

    private void backfillNames() {
        Document missing = new Document("nameNormalized", new Document("$exists", false));
        for (Document user : mongoTemplate.getCollection("usuarios").find(missing)) {
            Object name = user.get("name");
            if (name instanceof String text) {
                mongoTemplate.getCollection("usuarios").updateOne(
                        new Document("_id", user.get("_id")),
                        new Document("$set", new Document("nameNormalized", text.toLowerCase(Locale.ROOT))));
            }
        }
    }

    private void createIndexes() {
        mongoTemplate.indexOps("usuarios").createIndex(
                new Index().on("nameNormalized", Sort.Direction.ASC).named("nameNormalized"));
        mongoTemplate.indexOps("usuarios").createIndex(
                new Index().on("createdAt", Sort.Direction.ASC).named("createdAt"));
        mongoTemplate.indexOps("usuarios").createIndex(
                new Index().on("updatedAt", Sort.Direction.ASC).named("updatedAt"));
    }
}
