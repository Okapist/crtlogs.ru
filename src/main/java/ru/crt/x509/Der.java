package ru.crt.x509;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Минимальный разбор DER — ровно столько, сколько нужно, чтобы прочитать из сертификата
 * то, чего JDK не отдаёт или отдаёт испорченным.
 *
 * <p>Байты приходят из публичного CT-лога, то есть от кого угодно, поэтому ни одна
 * длина здесь не берётся на веру: непрочитанная запись — это null, а не исключение
 * из середины разбора.
 */
public final class Der {

    private Der() {
    }

    /** Универсальные теги, которые встречаются в разбираемых здесь местах. */
    public static final int OCTET_STRING = 0x04;
    public static final int UTF8_STRING = 0x0C;
    public static final int PRINTABLE_STRING = 0x13;
    public static final int T61_STRING = 0x14;
    public static final int IA5_STRING = 0x16;
    public static final int BMP_STRING = 0x1E;
    public static final int SEQUENCE = 0x30;

    /**
     * Один разобранный TLV: тег, границы содержимого и конец записи. Единственный
     * конструктор — {@link #at}, который либо возвращает заведомо корректные границы,
     * либо null: проверять их заново на каждой площадке разбора не нужно.
     *
     * <p>Границы верны для того массива, из которого запись разобрана, и только для
     * него: {@link #value} и {@link #text} принимают массив ещё раз, и подставить туда
     * другой — значит прочитать содержимое, которого в разобранном TLV не было.
     * Поэтому тип не выходит за пакет: за пределами {@code ru.crt.x509} держать эту
     * связь пришлось бы каждому вызывающему, а внутри — трём классам, написанным
     * вместе. Наружу отдаются готовые имена ({@link CertificateNames.GeneralName}),
     * а не курсоры по буферу.
     */
    record Tlv(int tag, int start, int length) {

        /** Конец записи — смещение следующего TLV. */
        int end() {
            return start + length;
        }

        byte[] value(byte[] der) {
            return Arrays.copyOfRange(der, start, end());
        }

        /** Содержимое строкой — читается прямо из der, без копии; кодировку задаёт вызывающий. */
        String text(byte[] der, Charset charset) {
            return new String(der, start, length, charset);
        }

        /** TLV по смещению off; границей служит конец массива. */
        static Tlv at(byte[] der, int off) {
            return at(der, off, der.length);
        }

        /**
         * TLV по смещению off или null, если запись не читается: выход за limit, длинная
         * форма тега, неопределённая форма длины, длина вне int или обрезанное содержимое.
         *
         * <p>limit — это конец не буфера, а того TLV, внутри которого идёт разбор.
         * Запись, чья длина вылезает за родителя, — не его часть: приняв её, разбор
         * приписал бы содержимому соседних байт смысл, которого в нём нет.
         */
        static Tlv at(byte[] der, int off, int limit) {
            if (off < 0 || limit > der.length || off + 1 >= limit) {
                return null;
            }
            // длинная форма тега: номер лежит в следующих байтах, и за длину мы приняли бы
            // байт продолжения тега, переразметив весь остаток. В разбираемых здесь местах
            // (GeneralName, значение атрибута DN) таких тегов не бывает
            if ((der[off] & 0x1F) == 0x1F) {
                return null;
            }
            int first = der[off + 1] & 0xFF;
            int count = first & 0x7F;
            int lengthBytes = 1;
            long length = count; // короткая форма: длина лежит прямо в этом байте
            if ((first & 0x80) != 0) { // длинная форма: следом идут count байт длины
                if (count == 0 || count > 4 || off + 1 + count >= limit) {
                    return null;
                }
                lengthBytes += count;
                length = 0;
                for (int i = 0; i < count; i++) {
                    length = (length << 8) | (der[off + 2 + i] & 0xFF);
                }
            }
            int start = off + 1 + lengthBytes;
            if (length > Integer.MAX_VALUE || start > limit || length > limit - start) {
                return null;
            }
            return new Tlv(der[off] & 0xFF, start, (int) length);
        }
    }

    /** getExtensionValue возвращает DER OCTET STRING — снимаем внешнюю обёртку. */
    public static byte[] unwrapOctetString(byte[] der) {
        if (der == null || der.length < 2 || der[0] != OCTET_STRING) {
            return der;
        }
        Tlv tlv = Tlv.at(der, 0);
        return tlv == null ? der : tlv.value(der); // обёртка обрезана — отдаём как есть
    }
}
