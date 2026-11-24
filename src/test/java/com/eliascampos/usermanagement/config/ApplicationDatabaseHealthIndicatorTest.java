package com.eliascampos.usermanagement.config;

import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationDatabaseHealthIndicatorTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private MongoDatabase database;

    @Test
    void shouldReportUpWhenApplicationDatabaseResponds() {
        when(mongoTemplate.getDb()).thenReturn(database);
        when(database.runCommand(any(Document.class))).thenReturn(new Document("ok", 1));
        when(database.getName()).thenReturn("livro-da-guilda");

        Health health = new ApplicationDatabaseHealthIndicator(mongoTemplate).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("livro-da-guilda", health.getDetails().get("database"));
    }

    @Test
    void shouldReportDownWhenApplicationDatabaseFails() {
        when(mongoTemplate.getDb()).thenReturn(database);
        when(database.runCommand(any(Document.class))).thenThrow(new MongoException("Mongo fora do ar"));

        Health health = new ApplicationDatabaseHealthIndicator(mongoTemplate).health();

        assertEquals(Status.DOWN, health.getStatus());
    }
}
