package com.carddemo.vsammq;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MqInquiryProgramTest {

    @Test
    void theErrorDisplayIsMqErrDisplayWithTruncatedCodes() {
        String display = MqInquiryProgram.errDisplay(2, 2085, "ERROR WHILE READING ACCTFILE", "CARDDEMO.REQUEST.QUEUE");

        assertEquals(113, display.length());
        assertEquals(" ".repeat(25) + "  ERROR WHILE READING ACCTF  02  02085  CARDDEMO.REQUEST.QUEUE",
                display.stripTrailing());
        assertEquals("  13  00017", MqInquiryProgram.errDisplay(113, 100017, "", "").substring(52, 63));
    }
}
