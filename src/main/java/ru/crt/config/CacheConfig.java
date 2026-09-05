package ru.crt.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Три кеша: certText — openssl-дамп по sha256 (один DER — одна запись на все логи),
 * search — 5 минут, caIds — issuer DN -> ca.id, без срока.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // Вес — длина дампа: у сертификата с сотнями SAN он на порядок больше типичного
        manager.registerCustomCache("certText", Caffeine.newBuilder()
                .maximumWeight(16_000_000)
                // дамп может быть null (DER не разобрался) — весовщик обязан это пережить
                .weigher((_, value) -> value instanceof String text ? text.length() : 1)
                .expireAfterAccess(Duration.ofHours(1))
                .build());
        manager.registerCustomCache("search", Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build());
        manager.registerCustomCache("caIds", Caffeine.newBuilder()
                .maximumSize(10_000)
                .build());
        return manager;
    }
}
