package ru.crt.x509;

import java.net.IDN;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Доменное имя в индексной форме (читаемый юникод) и приведение к ней любой записи. Форма одна на приём, индекс и запрос — иначе {@code %.рф} и {@code %.xn--p1ai} были бы двумя разными поисками.
 * Юникод, а не punycode: шаблон LIKE режет имя в любом месте.
 */
public final class DomainName {

    /** Префикс закодированного ярлыка (ACE prefix, RFC 3490). */
    private static final String ACE = "xn--";

    /** RFC 1035: имени длиннее 253 символов не бывает; cert_domain.domain — VARCHAR(255). */
    public static final int MAX_LENGTH = 253;

    /** Письменности, законно уживающиеся в одном ярлыке (UTS #39); прочее смешение — попытка выдать одно имя за другое. */
    private static final List<Set<Character.UnicodeScript>> MIXABLE = List.of(
            Set.of(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA),
            Set.of(Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL),
            Set.of(Character.UnicodeScript.HAN, Character.UnicodeScript.BOPOMOFO));

    private DomainName() {
    }

    /**
     * Имя в нижнем регистре и в punycode — шаг, на котором сходятся записи одного имени, отличающиеся письменностью, регистром или нормализацией.
     * Кодируется только ярлык с не-ASCII символами; подстановочные знаки по его краям отделяются и приставляются обратно, а ярлык, разрезанный знаком посередине, или ярлык с {@code _} не трогается.
     */
    public static String toAscii(String domain) {
        if (domain == null) {
            return null;
        }
        String lower = domain.toLowerCase(Locale.ROOT);
        // почти весь трафик — обычные ASCII-имена: до разбора на ярлыки не доходит
        return isAscii(lower) ? lower : mapLabels(lower, DomainName::labelToAscii);
    }

    /** Форма, в которой имя лежит в {@code cert_domain.domain} и в которой его ищут: расшифрованный юникод, если он влезает в колонку, иначе имя как записано (nameprep умеет удлинить, а выбрасывать нельзя — иначе сертификат потеряет единственное имя). */
    public static String toIndexForm(String name) {
        String canonical = toUnicode(toAscii(name));
        if (canonical == null || canonical.length() <= MAX_LENGTH) {
            return canonical;
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /** Та же форма задом наперёд — то, что лежит в {@code cert_domain.domain_reversed}. */
    public static String reverse(String domain) {
        return new StringBuilder(domain).reverse().toString();
    }

    /** Читаемый вид имени: {@code xn--80aasee0af7fwb.xn--p1ai} -> {@code нашажизнь.рф}; ярлык, расшифровке которого верить нельзя, остаётся как есть. */
    public static String toUnicode(String domain) {
        if (domain == null) {
            return null;
        }
        // расшифровывать нечего, пока нет ни одного закодированного ярлыка
        return hasAce(domain) ? mapLabels(domain, l -> mapCore(l, DomainName::coreToUnicode)) : domain;
    }

    /** Расшифровка всех идентификаторов; порядок задаётся заново, по показанным именам. */
    public static List<String> toUnicodeAll(List<String> domains) {
        return domains.stream().map(DomainName::toUnicode).sorted().toList();
    }

    private static String labelToAscii(String label) {
        if (isAscii(label)) {
            return label;
        }
        // '_' не переставить: он считает символы, а punycode меняет и их число, и порядок
        if (label.indexOf('_') >= 0) {
            return label;
        }
        return mapCore(label, core -> {
            try {
                return IDN.toASCII(core);
            } catch (RuntimeException notALabel) {
                // длиннее 63, пустой, запрещённый символ — не IDN, а строка из лога
                return core;
            }
        });
    }

    private static String coreToUnicode(String core) {
        if (!core.regionMatches(true, 0, ACE, 0, ACE.length())) {
            return core;
        }
        String decoded = IDN.toUnicode(core);
        // toUnicode не бросает исключений: непонятный ему ярлык он отдаёт как есть
        if (decoded.equals(core) || !readable(decoded) || !encodesBackTo(decoded, core)) {
            return core;
        }
        return decoded;
    }

    /** Преобразует ярлык, сохраняя подстановочные знаки по краям; ярлык, разрезанный знаком посередине, не трогается вовсе. */
    private static String mapCore(String label, UnaryOperator<String> core) {
        int from = 0;
        int to = label.length();
        while (from < to && label.charAt(from) == '%') {
            from++;
        }
        while (to > from && label.charAt(to - 1) == '%') {
            to--;
        }
        String middle = label.substring(from, to);
        if (middle.isEmpty() || middle.indexOf('%') >= 0) {
            return label;
        }
        return label.substring(0, from) + core.apply(middle) + label.substring(to);
    }

    /** Кодируется ли расшифровка обратно в тот же ярлык — иначе показать значит соврать. */
    private static boolean encodesBackTo(String decoded, String label) {
        try {
            return IDN.toASCII(decoded).equalsIgnoreCase(label);
        } catch (RuntimeException notALabel) {
            return false;
        }
    }

    /**
     * Можно ли показывать расшифровку: только буквы, цифры, диакритика, дефис и одна письменность (законные сочетания — {@link #MIXABLE}).
     * Ярлык, не прошедший проверку, остаётся punycode'ом везде, но находимым быть не перестаёт: правило одно на все стороны.
     */
    private static boolean readable(String label) {
        Set<Character.UnicodeScript> scripts = EnumSet.noneOf(Character.UnicodeScript.class);
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            i += Character.charCount(cp);
            if (!isNameCharacter(cp)) {
                return false;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            // COMMON — цифры и дефис, INHERITED — диакритика: ни с какой письменностью не спорят
            if (script != Character.UnicodeScript.COMMON
                    && script != Character.UnicodeScript.INHERITED) {
                scripts.add(script);
            }
        }
        return scripts.size() <= 1 || MIXABLE.stream().anyMatch(m -> m.containsAll(scripts));
    }

    private static boolean isNameCharacter(int cp) {
        int type = Character.getType(cp);
        return cp == '-' || Character.isLetterOrDigit(cp)
                || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** Есть ли в имени где-либо {@code xn--}: если нет, и разбирать на ярлыки незачем. */
    private static boolean hasAce(String domain) {
        for (int i = 0; i + ACE.length() <= domain.length(); i++) {
            if (domain.regionMatches(true, i, ACE, 0, ACE.length())) {
                return true;
            }
        }
        return false;
    }

    /** Преобразует по ярлыкам: и punycode, и проверка письменности определены на одном ярлыке; пустые ярлыки сохраняются. */
    private static String mapLabels(String domain, UnaryOperator<String> label) {
        String[] labels = domain.split("\\.", -1);
        for (int i = 0; i < labels.length; i++) {
            labels[i] = label.apply(labels[i]);
        }
        return String.join(".", labels);
    }
}
