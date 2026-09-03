package ru.crt.x509;

import java.net.IDN;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Доменное имя в том виде, в каком оно лежит в индексе и в каком его показывают, —
 * читаемым юникодом ({@link #toIndexForm}), — и приведение к нему любой записи имени.
 *
 * <p>Одно и то же имя приезжает в логи в двух формах. Большинство УЦ кладут в dNSName
 * уже закодированное {@code xn--80aasee0af7fwb.xn--p1ai}, а Минцифры — то же имя
 * кириллицей в UTF-8 (см. {@link CertificateNames}). Пока индекс повторял, кто как
 * записал, {@code %.рф} и {@code %.xn--p1ai} были двумя разными запросами и каждый
 * находил свою половину базы. Поэтому в {@code cert_domain} едет одна форма на всех, и
 * через неё же проходит поисковый запрос.
 *
 * <p>Форма эта — юникод, а не punycode, потому что по индексу ищут шаблоном LIKE, а
 * шаблон режет имя в произвольном месте. Punycode же кодирует ярлык целиком, меняя и
 * число символов, и их порядок: у {@code троят} код {@code xn--n1adfc8d}, у
 * {@code достроят} — {@code xn--d1auahegc7h}, и никаким LIKE из первого второе не
 * получить. На punycode-индексе {@code %троят.рф} не находил {@code достроят.рф},
 * тогда как {@code %stroyat.ru} для латиницы работал: один и тот же запрос вёл себя
 * по-разному в зависимости от письменности. На юникоде обе стороны — просто текст, и
 * {@code %} с {@code _} работают в любом месте имени одинаково.
 *
 * <p>Сводится к этой форме имя через punycode — {@link #toAscii}, а затем
 * {@link #toUnicode} обратно. Промежуточный шаг нужен не ради письменности: он же
 * приводит записи, отличающиеся регистром и нормализацией (nameprep), — иначе
 * {@code й} одним символом и {@code й} с отдельным кратким остались бы разными именами.
 *
 * <p>Расшифровка при этом не безусловна: punycode умеет закодировать и {@code аpple} с
 * кириллической «а», неотличимое от латинского. Такой ярлык остаётся закодированным —
 * см. {@link #readable}. Ненаходимым его это не делает: правило одно на обе стороны,
 * так что в запросе он остаётся закодированным ровно так же, как в индексе.
 */
public final class DomainName {

    /** Префикс закодированного ярлыка (ACE prefix, RFC 3490). */
    private static final String ACE = "xn--";

    /** RFC 1035: имени длиннее 253 символов не бывает; cert_domain.domain — VARCHAR(255). */
    public static final int MAX_LENGTH = 253;

    /**
     * Наборы письменностей, которые в одном ярлыке уживаются законно (UTS #39):
     * японский пишется ханьцзи с каной, корейский — с хангылем, тайваньский — с чжуинем.
     * Всё остальное смешение — кириллица с латиницей в первую очередь — это попытка
     * выдать одно имя за другое, и расшифровывать её мы не беремся.
     */
    private static final List<Set<Character.UnicodeScript>> MIXABLE = List.of(
            Set.of(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA),
            Set.of(Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL),
            Set.of(Character.UnicodeScript.HAN, Character.UnicodeScript.BOPOMOFO));

    private DomainName() {
    }

    /**
     * Имя в нижнем регистре и в punycode — промежуточный шаг {@link #toIndexForm}, на
     * котором сходятся записи одного имени, отличающиеся письменностью, регистром или
     * нормализацией.
     *
     * <p>Переводится только ярлык, в котором есть не-ASCII символ: всё остальное —
     * {@code *}, {@code _acme-challenge}, ярлык длиннее 63 символов, вообще любая
     * строка, которую подавший сертификат счёл именем, — проходит буква в букву.
     * Иначе канонизация меняла бы имена, к IDN отношения не имеющие, а {@code toASCII}
     * на половине из них бросает исключение.
     *
     * <p>Подстановочный знак по краям ярлыка ({@code %домрфбанк}) от него отделяется, а
     * переводится то, что между знаками: иначе {@code %} попал бы в кодируемый ярлык и
     * {@code toASCII} закодировал бы его вместе с буквами. Ярлык, разрезанный знаком
     * посередине ({@code до%банк}), и {@code _} в любом месте не переводятся вовсе:
     * половине ярлыка в punycode не соответствует ничего, а {@code _} считает символы,
     * число которых кодирование меняет. На поиске это не сказывается —
     * {@link #toUnicode} возвращает такой ярлык в тот же вид, в каком он лежит в
     * индексе, и шаблон сравнивается с именем как обычный текст.
     */
    public static String toAscii(String domain) {
        if (domain == null) {
            return null;
        }
        String lower = domain.toLowerCase(Locale.ROOT);
        // почти весь трафик — обычные ASCII-имена: до разбора на ярлыки не доходит,
        // а зовётся это на каждом dNSName каждой синхронизируемой записи лога
        return isAscii(lower) ? lower : mapLabels(lower, DomainName::labelToAscii);
    }

    /**
     * Форма, в которой имя лежит в {@code cert_domain.domain} и в которой его там ищут:
     * расшифрованный юникод, если он влезает в колонку, иначе имя как записано.
     *
     * <p>Оговорка нужна из-за nameprep: он не только приводит регистр, но и раскрывает
     * совместимые символы ({@code ß} становится {@code ss}), так что имя, которое в
     * колонку влезало, после приведения может уже не влезть. Просто выбросить такое имя
     * нельзя: сертификат, у которого другого имени нет, не попал бы в cert_domain вовсе,
     * а поиск join'ит его с certificate — то есть сертификат перестал бы находиться
     * любым запросом, молча и без строки в журнале.
     *
     * <p>Правило одно на обе стороны — и на приём ({@code CertificateNames}), и на запрос:
     * разойдись они, имя лежало бы в базе в одной форме, а искалось в другой, и результат
     * был бы тот же, что от выбрасывания.
     */
    public static String toIndexForm(String name) {
        String canonical = toUnicode(toAscii(name));
        if (canonical == null || canonical.length() <= MAX_LENGTH) {
            return canonical;
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Та же форма задом наперёд — то, что лежит в {@code cert_domain.domain_reversed} и
     * во что сворачивается шаблон поддоменов ({@code CertificateRepository.DomainMatch}).
     *
     * <p>Обе поисковые колонки описаны здесь, одним классом: у формы, по которой ищут,
     * должно быть одно место, иначе её приходится переписывать всюду, где к базе ходят
     * не через репозиторий.
     */
    public static String reverse(String domain) {
        return new StringBuilder(domain).reverse().toString();
    }

    /**
     * Читаемый вид имени: {@code xn--80aasee0af7fwb.xn--p1ai} -> {@code нашажизнь.рф}.
     * Ярлык, расшифровке которого верить нельзя, остаётся как есть — имя может оказаться
     * наполовину расшифрованным, и это честнее, чем показать подделку.
     *
     * <p>Подстановочные знаки по краям ярлыка отделяются так же, как в {@link #toAscii}:
     * расшифровка — вторая половина приведения к индексной форме, и через неё проходит
     * не только имя из сертификата, но и поисковый шаблон ({@code %xn--n1adfc8d.xn--p1ai}
     * — это {@code %} и ярлык, а не полтора ярлыка).
     */
    public static String toUnicode(String domain) {
        if (domain == null) {
            return null;
        }
        // расшифровывать нечего, пока в имени нет ни одного закодированного ярлыка, — а
        // зовётся это на каждое имя каждой показанной строки выдачи
        return hasAce(domain) ? mapLabels(domain, l -> mapCore(l, DomainName::coreToUnicode)) : domain;
    }

    /**
     * То же для всех идентификаторов сертификата разом. Отдельным именем, а не
     * перегрузкой: у перегруженного по {@code String} и {@code List} метода вызов с
     * {@code null} не компилируется вовсе.
     *
     * <p>Порядок задаётся заново, по показанным именам: из БД список приходит
     * отсортированным по хранимой форме, а в ней остаются закодированными ярлыки,
     * расшифровке которых верить нельзя, — они стояли бы в списке где попало.
     */
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
                // длиннее 63 символов, пустой, запрещённый символ — это не IDN, а просто
                // строка из публичного лога: пусть ищется и показывается как записана
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

    /**
     * Преобразование ярлыка с сохранением подстановочных знаков по краям: знаки
     * снимаются и приставляются обратно, а преобразуется то, что между ними. Ярлык,
     * разрезанный знаком посередине ({@code до%банк}), и ярлык из одних знаков не
     * трогаются вовсе: половине ярлыка ни в punycode, ни в его расшифровке не
     * соответствует ничего.
     */
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

    /**
     * Кодируется ли расшифровка обратно ровно в тот ярлык, из которого получена. Punycode
     * — не единственный способ записать эти байты, и показать имя, которое кодируется во
     * что-то другое, значит соврать: посетитель, скопировав его из выдачи, искал бы
     * потом не тот сертификат.
     */
    private static boolean encodesBackTo(String decoded, String label) {
        try {
            return IDN.toASCII(decoded).equalsIgnoreCase(label);
        } catch (RuntimeException notALabel) {
            return false;
        }
    }

    /**
     * Можно ли показывать расшифровку вместо punycode. Два условия, и оба про обман:
     *
     * <ul>
     *   <li>в ярлыке только буквы, цифры, диакритика и дефис — то, из чего имя и
     *       состоит. Punycode кодирует любой символ, в том числе похожие на разделители
     *       ({@code ∕}, {@code ｡}) и невидимые, а из них собирается имя, читающееся не
     *       так, как оно есть;
     *   <li>письменность одна. {@code xn--pple-43d} — это {@code аpple} с кириллической
     *       «а» и латинским хвостом, отличить его от {@code apple} на глаз нельзя.
     *       Законные сочетания перечислены в {@link #MIXABLE}.
     * </ul>
     *
     * <p>Ярлык, не прошедший проверку, остаётся punycode'ом — и в выдаче, и в индексе, и
     * в поисковом запросе. Ненаходимым его это не делает: правило одно на все три
     * стороны, поэтому {@code аpple.com} с кириллической «а» и
     * {@code xn--pple-43d.com} — по-прежнему один и тот же запрос.
     *
     * <p>Граница у проверки есть: имя, целиком набранное похожими на латиницу буквами
     * одной чужой письменности, письменностей не смешивает и расшифровывается. Ловить
     * такое — это таблица начертательно неразличимых символов (UTS #39 confusables),
     * которую пришлось бы возить с собой и обновлять; браузеры её возят, а здесь
     * подделка стоит рядом с дампом сертификата и ссылкой на сам CT-лог, где имя видно
     * как есть.
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
            // COMMON — это цифры и дефис, INHERITED — диакритика: они не спорят ни с
            // какой письменностью и сами по себе ярлык одноязычным быть не мешают
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

    /**
     * Есть ли в имени хоть один закодированный ярлык. Ищем где угодно, а не в начале
     * ярлыка: расшифровке предшествует разбор на ярлыки, а его-то мы и хотим не делать.
     * Промах в другую сторону безобиден — {@link #coreToUnicode} проверит начало сам.
     */
    private static boolean hasAce(String domain) {
        for (int i = 0; i + ACE.length() <= domain.length(); i++) {
            if (domain.regionMatches(true, i, ACE, 0, ACE.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Преобразование по ярлыкам. Именно по ярлыкам, а не по имени целиком: и punycode, и
     * проверка письменности определены на одном ярлыке, а {@code www.мойсайт.рф} смешивает
     * латиницу с кириллицей на каждом втором сайте рунета и обманом не является.
     *
     * <p>Пустые ярлыки сохраняются ({@code -1} у split): {@code a..b} — не имя, но и
     * молча превращать его в {@code a.b} мы не вправе, эта строка приехала из лога.
     */
    private static String mapLabels(String domain, UnaryOperator<String> label) {
        String[] labels = domain.split("\\.", -1);
        for (int i = 0; i < labels.length; i++) {
            labels[i] = label.apply(labels[i]);
        }
        return String.join(".", labels);
    }
}
