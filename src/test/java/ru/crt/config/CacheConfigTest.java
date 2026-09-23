package ru.crt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

/** Вес записи certText считается по длине дампа; наполнение кешей проверяют интеграционные тесты. */
class CacheConfigTest {

    private final CacheManager caches = new CacheConfig().cacheManager();

    @Test
    void dumpIsStoredUnderItsFingerprint() {
        caches.getCache(CacheConfig.CERT_TEXT).put("abc", "Certificate:\n    Data:\n");
        assertEquals("Certificate:\n    Data:\n", caches.getCache(CacheConfig.CERT_TEXT).get("abc").get());
    }

    /** Дамп может быть null (DER не разобрался) — весовщик обязан это пережить. */
    @Test
    void certificateWithoutTextDumpCanBeCached() {
        caches.getCache(CacheConfig.CERT_TEXT).put("unparseable", null);

        assertNotNull(caches.getCache(CacheConfig.CERT_TEXT).get("unparseable"), "запись есть");
        assertNull(caches.getCache(CacheConfig.CERT_TEXT).get("unparseable").get(), "и она пустая");
    }
}
