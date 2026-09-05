package ru.crt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.crt.AbstractIntegrationTest;

/** Настройки {@code ct.*} биндятся из yml; опечатка в ключе молча оставляет дефолты. */
class AppPropertiesTest extends AbstractIntegrationTest {

    @Autowired
    private AppProperties props;

    /** Ключи в yml — kebab-case, поля — camelCase: связывает их relaxed binding Spring Boot. */
    @Test
    void valuesFromTestYamlOverrideTheDefaults() {
        // src/test/resources/application.yml (он подменяет основной целиком)
        assertEquals("http://localhost:1/none", props.getLogListUrl());
        assertEquals("test-token", props.getAuth().getToken());
        assertFalse(props.getRateLimit().isEnabled(), "вложенный ключ rate-limit.enabled должен доезжать");
    }

    /** Значения по умолчанию живут в Java, а не дублируются в yml, — тут они и закреплены. */
    @Test
    void defaultsAreSafeWhenNothingIsConfigured() {
        AppProperties defaults = new AppProperties();
        assertEquals(256, defaults.getBatchSize());
        assertEquals(1.0, defaults.getRateLimit().getRps());
        assertEquals(3, defaults.getRateLimit().getBurst());
        assertTrue(defaults.getRateLimit().isEnabled(), "лимит включён, пока его не выключили явно");
        assertFalse(defaults.getRateLimit().isTrustForwarded(), "заголовкам клиента по умолчанию не верим");
        assertFalse(defaults.getAuth().isProtectAll(), "по умолчанию закрыт только /api/logs");
        assertEquals("", defaults.getAuth().getToken(), "пустой токен => закрытые эндпоинты отвечают 401");
    }
}
