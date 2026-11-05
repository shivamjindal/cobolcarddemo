package com.carddemo.batch.programs;

import java.math.BigDecimal;

import com.carddemo.records.Fields;

/** A signed DISPLAY amount. Storing it drops extra fraction and high-order digits toward zero, as COMPUTE into a picture does. */
final class SignedMoney {

    private static final int SCALE = 2;

    private SignedMoney() {}

    static BigDecimal read(byte[] record, int offset, int length) {
        return Fields.zoned(record, offset, length, SCALE);
    }

    static void write(byte[] record, int offset, int length, BigDecimal value) {
        Fields.zoned(record, offset, length, SCALE, true, value);
    }

    /** The value held by {@code PIC S9(integerDigits)V99}. */
    static BigDecimal store(BigDecimal value, int integerDigits) {
        int length = integerDigits + SCALE;
        byte[] held = new byte[length];
        write(held, 0, length, value);
        return read(held, 0, length);
    }
}
