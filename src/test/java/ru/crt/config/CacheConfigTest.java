package ru.crt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

/**
 * Вес записи certText считается по длине дампа. Что кеши наполняются и по каким ключам,
 * показывают CertDetailsServiceTest и CertificateRepositoryTest (@Cacheable работает
 * только через прокси, поэтому проверять это в отрыве от бинов нечем).
 *
 * <p>Контекст здесь не нужен: cacheManager() — обычный @Bean-метод, и весовщик считается
 * тем же кодом независимо от того, кто собрал CacheConfig.
 */
class CacheConfigTest {

    private final CacheManager caches = new CacheConfig().cacheManager();

    @Test
    void dumpIsStoredUnderItsFingerprint() {
        caches.getCache("certText").put("abc", "Certificate:\n    Data:\n");
        assertEquals("Certificate:\n    Data:\n", caches.getCache("certText").get("abc").get());
    }

    /**
     * Дамп не строится для сертификата, который не разобрал CertificateFactory, — тогда
     * в кеш кладётся null. Весовщик обязан это пережить: иначе страница такого
     * сертификата падала бы в момент попадания в кеш.
     */
    @Test
    void certificateWithoutTextDumpCanBeCached() {
        caches.getCache("certText").put("unparseable", null);

        assertNotNull(caches.getCache("certText").get("unparseable"), "запись есть");
        assertNull(caches.getCache("certText").get("unparseable").get(), "и она пустая");
    }
}
