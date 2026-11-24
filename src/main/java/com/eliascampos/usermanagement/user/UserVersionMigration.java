package com.eliascampos.usermanagement.user;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Documentos criados antes da introducao do campo @Version nao possuem o campo.
 * Sem isso, o Spring Data trata o documento como novo e o save() vira insert,
 * causando chave duplicada. Normaliza esses documentos na subida.
 */
@Component
public class UserVersionMigration implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    public UserVersionMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Query query = new Query(Criteria.where("version").exists(false));
        Update update = new Update().set("version", 0L);
        mongoTemplate.updateMulti(query, update, User.class);
    }
}