package com.eliascampos.usermanagement.config;

import org.bson.Document;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Substitui o health indicator padrao do Spring Boot 4, que lista todos os bancos
 * e executa `hello` em cada um, inclusive `local`. O usuario da demo no Atlas tem
 * privilegio minimo e nao tem acesso a `local`, entao o health padrao derrubava
 * o deploy. Aqui so fazemos ping no banco configurado para a aplicacao.
 */
@Component("applicationMongo")
public class ApplicationDatabaseHealthIndicator extends AbstractHealthIndicator {

    private final MongoTemplate mongoTemplate;

    public ApplicationDatabaseHealthIndicator(MongoTemplate mongoTemplate) {
        super("MongoDB indisponivel");
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        mongoTemplate.getDb().runCommand(new Document("ping", 1));
        builder.up().withDetail("database", mongoTemplate.getDb().getName());
    }
}
