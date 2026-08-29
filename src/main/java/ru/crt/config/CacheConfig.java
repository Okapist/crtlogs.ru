package ru.crt.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Два кеша с разной политикой. certById — долго: DER и текстовый дамп неизменны,
 * а меняющийся кусок деталей (occurrences — список логов с этим сертификатом)
 * сбрасывается явно из CertificateRepository.insertCertificates при синке.
 * search — 5 мин: синк раз в 10 мин, а пейджинг по большой выборке не должен
 * пересчитывать счётчик и окно на каждом листании.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache("certById", Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(Duration.ofHours(1))
                .build());
        // записей больше, чем раньше (счётчик + по записи на страницу), но каждая теперь
        // не больше сотни id, а не вся выборка целиком — суммарно это единицы мегабайт
        manager.registerCustomCache("search", Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build());
        return manager;
    }
}
