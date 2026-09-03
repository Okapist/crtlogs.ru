package ru.crt.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Три кеша с разной политикой.
 *
 * <ul>
 *   <li>certText — openssl-дамп сертификата, единственная дорогая часть его деталей.
 *       Ключ — sha256, а не id: дамп зависит только от DER, поэтому одна запись
 *       обслуживает все вхождения сертификата в разные логи и не устаревает от синка.
 *       Кеш целых CertDetails так не мог: список occurrences внутри менялся с каждой
 *       вставленной пачкой, и сбрасывать приходилось всё.</li>
 *   <li>search — 5 мин: синк раз в 10 мин, а повторный запрос той же страницы не должен
 *       заново сканировать cert_domain.</li>
 *   <li>caIds — issuer DN -> ca.id. Строки ca не удаляются и не меняются, поэтому
 *       без срока жизни; ограничение по размеру нужно только как страховка.</li>
 * </ul>
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // Лимит по весу, а не по числу записей: вес записи — это openssl-дамп, а он
        // пропорционален числу SAN в сертификате. У типичного сертификата это ~8 тысяч
        // символов, у сертификата с сотнями доменов — на порядок больше, и тысяча таких
        // записей не должна была бы поместиться в heap целиком. 16 млн символов — это
        // около 32 МБ в UTF-16, то есть ~2000 обычных записей.
        manager.registerCustomCache("certText", Caffeine.newBuilder()
                .maximumWeight(16_000_000)
                // дамп не строится для сертификата, который не разобрал CertificateFactory:
                // в кеш тогда ложится обёртка над null, и весовщик обязан её пережить
                .weigher((_, value) -> value instanceof String text ? text.length() : 1)
                .expireAfterAccess(Duration.ofHours(1))
                .build());
        // одна запись на (запрос, страница), не больше сотни id каждая — суммарно это
        // единицы мегабайт
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
