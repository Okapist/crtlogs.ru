package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.crt.AbstractIntegrationTest;
import ru.crt.TestFixtures;
import ru.crt.db.CertificateRepository;

/** Детали сертификата: поля контракта, вхождения в логи и кеш дампа. */
class CertDetailsServiceTest extends AbstractIntegrationTest {

    @Test
    void detailsAreBuiltFromTheStoredCertificateAndMissingIdGivesNull() throws Exception {
        CertificateRepository.NewCert inserted = insertFixture();
        long id = idsOf(FIXTURE_DOMAIN).getFirst();

        CertDetails details = certDetailsService.details(id);
        assertNotNull(details);
        assertEquals(id, details.id());
        assertEquals(inserted.sha256(), details.sha256());
        assertEquals(40, details.sha1().length(), "SHA-1 в hex — 40 символов");
        assertEquals(LOG_URL, details.logUrl());
        assertEquals(0, details.logIndex());
        assertEquals("cateam.ru", details.commonName());
        assertEquals(List.of("cateam.ru", "www.cateam.ru"), details.domains());
        assertEquals("2026-01-19T10:26:30", details.notBefore(), "даты — ISO без зоны, как у crt.sh");
        assertFalse(details.precert());
        assertNotNull(details.text(), "дамп сертификата строится при выдаче деталей");
        assertTrue(details.pem().startsWith("-----BEGIN CERTIFICATE-----"));

        assertNull(certDetailsService.details(-1));
    }

    @Test
    void occurrencesListEveryLogTheSameCertificateWasFoundIn() throws Exception {
        CertificateRepository.NewCert original = insertFixture();
        long id = idsOf(FIXTURE_DOMAIN).getFirst();
        assertEquals(1, certDetailsService.details(id).occurrences().size());

        repository.insertCertificates(List.of(inLog(original, "https://second.example/log/", 7)));

        assertEquals(2, certDetailsService.details(id).occurrences().size(),
                "новое вхождение должно быть видно сразу, а не через час");
    }

    /** Страница показывает расшифрованное; дамп печатает как записано. */
    @Test
    void cyrillicIdentifiersAreShownDecodedWhileTheDumpStaysLiteral() throws Exception {
        insertCyrillicCert();
        long id = idsOf(TestFixtures.CYRILLIC_DOMAIN).getFirst();

        CertDetails details = certDetailsService.details(id);
        assertEquals(List.of(TestFixtures.CYRILLIC_DOMAIN), details.domains());
        assertEquals(TestFixtures.CYRILLIC_DOMAIN, details.nameValue());
        assertEquals(TestFixtures.CYRILLIC_DOMAIN, details.commonName());
        assertTrue(details.text().contains(TestFixtures.CYRILLIC_DOMAIN),
                "дамп печатает dNSName так, как он записан в сертификате");
    }

    /** Кеш по отпечатку: у одного сертификата в разных логах один DER. */
    @Test
    void dumpIsCachedBySha256AndSurvivesInsert() throws Exception {
        CertificateRepository.NewCert original = insertFixture();
        long id = idsOf(FIXTURE_DOMAIN).getFirst();

        String dump = certDetailsService.details(id).text();
        assertNotNull(cacheManager.getCache("certText").get(original.sha256()),
                "дамп должен попасть в кеш certText под отпечатком сертификата");

        repository.insertCertificates(List.of(inLog(original, "https://second.example/log/", 8)));

        assertNotNull(cacheManager.getCache("certText").get(original.sha256()),
                "вставка не сбрасывает кеш дампа: DER сертификата не меняется");
        assertEquals(dump, certDetailsService.details(id).text());
    }
}
