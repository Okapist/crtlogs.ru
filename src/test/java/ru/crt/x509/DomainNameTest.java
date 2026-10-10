package ru.crt.x509;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.crt.TestFixtures;

/** Индексная форма и расшифровка — одно правило, проверяются на одних строках. */
class DomainNameTest {

    /** Домен фикстуры Минцифры: в сертификате записан кириллицей, им же и ищется. */
    private static final String CYRILLIC = TestFixtures.CYRILLIC_DOMAIN;
    private static final String PUNYCODE = TestFixtures.CYRILLIC_DOMAIN_ASCII;

    @Test
    void bothFormsOfTheSameNameCollapseToOneIndexForm() {
        assertEquals(CYRILLIC, DomainName.toIndexForm(CYRILLIC), "индексная форма идемпотентна");
        assertEquals(CYRILLIC, DomainName.toIndexForm(PUNYCODE));
        assertEquals(CYRILLIC, DomainName.toIndexForm("Антоненков.РФ"), "и регистр тоже");
    }

    @Test
    void wildcardKeepsItsPlaceWhileTheRestIsTranslated() {
        assertEquals("%.рф", DomainName.toIndexForm("%.рф"));
        assertEquals("%.рф", DomainName.toIndexForm("%.xn--p1ai"));
        assertEquals("%.антоненков.рф", DomainName.toIndexForm("%.xn--80aeiunabfe8a.xn--p1ai"));
        assertEquals("_.рф", DomainName.toIndexForm("_.рф"), "_ — тоже подстановочный знак LIKE");
    }

    /** Ради этого юникод-индекс и держат: знак режет кириллический ярлык как латинский. */
    @Test
    void aWildcardCutsACyrillicLabelJustLikeALatinOne() {
        assertEquals("%троят.рф", DomainName.toIndexForm("%троят.рф"));
        assertEquals("достроят.рф", DomainName.toIndexForm("xn--d1auahegc7h.xn--p1ai"),
                "иначе шаблону выше в базе нечего было бы находить");
        assertEquals("%домрфбанк.рф", DomainName.toIndexForm("%домрфбанк.рф"));
        assertEquals("домрфбанк.рф%", DomainName.toIndexForm("домрфбанк.рф%"));
        assertEquals("%домрфбанк%", DomainName.toIndexForm("%домрфбанк%"));
        assertEquals("до%банк.рф", DomainName.toIndexForm("до%банк.рф"), "знак посередине");
        assertEquals("%домрфбан_.рф", DomainName.toIndexForm("%домрфбан_.рф"), "_ считает символы");
        assertEquals("%vk%", DomainName.toIndexForm("%vk%"), "латиница как была");
    }

    @Test
    void punycodeIsTheStepWhereTheTwoRecordingsMeet() {
        assertEquals(PUNYCODE, DomainName.toAscii(CYRILLIC));
        assertEquals(PUNYCODE, DomainName.toAscii(PUNYCODE), "шаг идемпотентен");
        assertEquals(PUNYCODE, DomainName.toAscii("Антоненков.РФ"), "и регистр тоже");
        assertEquals("%xn--80abi0ahidr0b.xn--p1ai", DomainName.toAscii("%домрфбанк.рф"));
        assertEquals("до%банк.xn--p1ai", DomainName.toAscii("до%банк.рф"));
        assertEquals("_домрфбанк.xn--p1ai", DomainName.toAscii("_домрфбанк.рф"));
    }

    @Test
    void asciiNamesAreNotTouchedAtAll() {
        assertEquals("*.example.ru", DomainName.toAscii("*.example.ru"));
        assertEquals("_acme-challenge.example.ru", DomainName.toAscii("_acme-challenge.example.ru"));
        assertEquals("a".repeat(70) + ".ru", DomainName.toAscii("a".repeat(70) + ".ru"),
                "ярлык длиннее 63 символов — не наше дело, это строка из публичного лога");
        assertEquals("a..b", DomainName.toAscii("a..b"), "пустой ярлык сохраняется");
        assertNull(DomainName.toAscii(null));
    }

    @Test
    void onlyTheLabelsThatCanBeTranslatedAre() {
        assertEquals("я".repeat(70) + ".xn--p1ai", DomainName.toAscii("я".repeat(70) + ".рф"));
    }

    @Test
    void aNameWhoseIndexFormDoesNotFitIsIndexedAsWritten() {
        String expanding = ("ß".repeat(30) + ".").repeat(5) + "ru";
        assertTrue(DomainName.toAscii(expanding).length() > 255,
                "иначе тест не проверяет ничего: форма обязана не влезать в VARCHAR(255)");
        assertEquals(expanding, DomainName.toIndexForm(expanding));
        assertNull(DomainName.toIndexForm(null));
    }

    @Test
    void reverseIsPlainStringReversal() {
        assertEquals("ur.elpmaxe.www", DomainName.reverse("www.example.ru"));
        assertEquals("", DomainName.reverse(""));
        assertEquals("www.example.ru", DomainName.reverse(DomainName.reverse("www.example.ru")));
    }

    // --- расшифровка ---

    @Test
    void punycodeIsShownDecodedAndTheRestIsShownAsIs() {
        assertEquals(CYRILLIC, DomainName.toUnicode(PUNYCODE));
        assertEquals("*.нашажизнь.рф", DomainName.toUnicode("*.xn--80aasee0af7fwb.xn--p1ai"));
        assertEquals("example.ru", DomainName.toUnicode("example.ru"));
        assertNull(DomainName.toUnicode(null));
    }

    /** Из БД список приходит по punycode — порядок задаётся заново, по показанным именам. */
    @Test
    void identifiersAreOrderedByWhatIsShown() {
        assertEquals(List.of("example.ru", CYRILLIC),
                DomainName.toUnicodeAll(List.of(PUNYCODE, "example.ru")));
    }

    @Test
    void asciiLabelsNextToCyrillicOnesAreNormal() {
        assertEquals("www.нашажизнь.рф", DomainName.toUnicode("www.xn--80aasee0af7fwb.xn--p1ai"));
        assertEquals("нашажизнь.com", DomainName.toUnicode("xn--80aasee0af7fwb.com"));
    }

    @Test
    void labelsMixingCyrillicWithLatinStayInPunycode() {
        assertEquals("xn--pple-43d.com", DomainName.toUnicode("xn--pple-43d.com"));
        assertEquals("аpple.com", java.net.IDN.toUnicode("xn--pple-43d") + ".com",
                "иначе тест проверял бы нерасшифровываемую строку и ничего не значил");
    }

    @Test
    void undecodablePunycodeIsShownAsItIs() {
        assertEquals("xn--", DomainName.toUnicode("xn--"));
        assertEquals("xn--a.ru", DomainName.toUnicode("xn--a.ru"));
        assertEquals("xn--80ak6aa92e.com", DomainName.toUnicode("xn--80ak6aa92e.com"));
    }

    /** Показанное имя обязано находиться. */
    @Test
    void whatIsShownCanBeSearchedFor() {
        for (String punycode : List.of(PUNYCODE, "xn--80aasee0af7fwb.com", "*.xn--p1ai",
                "xn--pple-43d.com", "xn--80ak6aa92e.com", "xn--a.ru")) {
            assertEquals(punycode, DomainName.toAscii(DomainName.toUnicode(punycode)),
                    "показанное имя должно приводиться к тому же канону, из которого получено");
        }
    }
}
