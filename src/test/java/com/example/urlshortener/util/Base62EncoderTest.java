package com.example.urlshortener.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class Base62EncoderTest {

    @Test
    void encodeAndDecode_areInverses() {
        long[] values = {0L, 1L, 61L, 62L, 12345L, 999999999L};
        for (long value : values) {
            String encoded = Base62Encoder.encode(value);
            assertEquals(value, Base62Encoder.decode(encoded));
        }
    }

    @Test
    void encode_producesDifferentCodesForDifferentIds() {
        assertNotEquals(Base62Encoder.encode(100L), Base62Encoder.encode(101L));
    }

    @Test
    void encode_zero_returnsFirstAlphabetChar() {
        assertEquals("0", Base62Encoder.encode(0L));
    }
}
