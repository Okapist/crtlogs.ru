package ru.crt.x509;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class DerTest {

    @Test
    void extensionValueIsUnwrappedFromItsOctetString() {
        assertArrayEquals(new byte[]{0x41, 0x42},
                Der.unwrapOctetString(new byte[]{0x04, 0x02, 0x41, 0x42}));
        byte[] notWrapped = {0x30, 0x02, 0x41, 0x42};
        assertSame(notWrapped, Der.unwrapOctetString(notWrapped), "не OCTET STRING — отдаём как есть");
        assertNull(Der.unwrapOctetString(null));
    }

    @Test
    void malformedDerLengthsAreRejectedInsteadOfThrowing() {
        assertNull(Der.Tlv.at(new byte[]{0x04, (byte) 0x84}, 0),
                "поле длины обещает 4 байта, а данных нет");
        assertNull(Der.Tlv.at(new byte[]{0x04, (byte) 0x84, -1, -1, -1, -1}, 0),
                "длина 0xFFFFFFFF не влезает в int");
        assertNull(Der.Tlv.at(new byte[]{0x04, 0x7F, 0x01, 0x02}, 0),
                "обещано 127 байт содержимого, есть 2");
        assertNull(Der.Tlv.at(new byte[]{0x04, 0x02, 0x41, 0x42}, -1), "смещение вне массива");
        byte[] truncated = {0x04, 0x7F, 0x01, 0x02};
        assertSame(truncated, Der.unwrapOctetString(truncated),
                "обрезанную обёртку отдаём как есть, а не копируем за границу массива");
    }

    /** Длинная форма тега: байт продолжения приняли бы за длину. */
    @Test
    void highTagNumberFormIsRejected() {
        assertNull(Der.Tlv.at(new byte[]{0x1F, 0x08, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77}, 0));
    }

    @Test
    void recordsThatOverrunTheirParentAreRejected() {
        byte[] der = {(byte) 0x82, 0x08, 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'};

        assertNotNull(Der.Tlv.at(der, 0, der.length));
        assertNull(Der.Tlv.at(der, 0, 6), "содержимое не помещается в родителя");
        assertNull(Der.Tlv.at(der, 0, der.length + 1), "limit за концом буфера");
    }

    @Test
    void tlvExposesTagContentAndOffsetOfTheNextRecord() {
        byte[] tlv = {0x04, 0x02, 0x41, 0x42, (byte) 0xFF}; // OCTET STRING "AB" + хвост
        Der.Tlv ok = Der.Tlv.at(tlv, 0);

        assertNotNull(ok);
        assertEquals(0x04, ok.tag());
        assertEquals(2, ok.length());
        assertEquals(4, ok.end(), "end указывает на следующий TLV");
        assertArrayEquals(new byte[]{0x41, 0x42}, ok.value(tlv));
    }
}
