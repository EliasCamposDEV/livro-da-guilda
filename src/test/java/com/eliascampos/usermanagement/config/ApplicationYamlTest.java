package com.eliascampos.usermanagement.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * O carregador do Spring Boot 4 rejeita chaves YAML repetidas. Um perfil com
 * chave duplicada so falha ao subir, o que derruba o deploy e passa batido nos
 * testes que usam application-test.yml.
 */
class ApplicationYamlTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "application.yml",
            "application-dev.yml",
            "application-demo.yml",
            "application-prod.yml",
            "application-test.yml"
    })
    void shouldLoadEveryProfileWithoutDuplicateKeys(String name) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(name, new ClassPathResource(name));

        assertFalse(sources.isEmpty(), name);
    }

    @Test
    void shouldKeepDemoReadOnlySettingAfterMergingKeys() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-demo", new ClassPathResource("application-demo.yml"));

        PropertySource<?> source = sources.get(0);
        assertEquals(true, source.getProperty("app.demo.read-only"));
        assertEquals("${DEMO_ADMIN_EMAIL:demo@eliascampos.dev}",
                source.getProperty("app.demo.admin.email"));
    }
}
