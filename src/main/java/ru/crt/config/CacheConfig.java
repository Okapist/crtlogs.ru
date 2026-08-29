package ru.crt.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Два кеша с разной политикой: сертификаты иммутабельны (certById — долго),
 * списки id поиска устаревают по мере синка (search — 5 мин: синк раз в 10 мин,
 * а пейджинг по большой выборке не должен пересчитывать её на каждом листании).
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
        manager.registerCustomCache("search", Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build());
        return manager;
    }
}
