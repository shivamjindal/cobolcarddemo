package com.carddemo.trantype;

import org.junit.jupiter.api.Test;

import com.carddemo.db2.Sqlca;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TranTypeListScreenTest {

    @Test
    void screenNumberIsOneDigitAndDropsItsSign() {
        assertEquals(2, TranTypeListScreen.screenNumAfter(1, +1));
        assertEquals(0, TranTypeListScreen.screenNumAfter(9, +1));
        assertEquals(1, TranTypeListScreen.screenNumAfter(0, -1));
        assertEquals(8, TranTypeListScreen.screenNumAfter(9, -1));
    }

    @Test
    void db2MessageShowsDsntiacReturnCodeTwelve() {
        Sqlca childRows = new Sqlca(-532, "23503", "", 0);
        String message = "Please delete associated child records first: SQLCODE: -532 DSNTIAC CD12";
        assertArrayEquals(new String[] {message, message},
                TranTypeListScreen.db2Message("Please delete associated child records first:", childRows));
    }

    @Test
    void db2MessageKeepsSeventyFiveCharactersForTheScreen() {
        Sqlca notFound = new Sqlca(100, "02000", "", 0);
        String[] messages = TranTypeListScreen.db2Message("Record not found. Deleted by others ? ", notFound);
        assertEquals("Record not found. Deleted by others ? SQLCODE:  100 DSNTIAC CD12", messages[0]);
        assertEquals(messages[0], messages[1]);
        String[] longAction = TranTypeListScreen.db2Message("x".repeat(72), notFound);
        assertEquals(72 + " SQLCODE:  100 DSNTIAC CD12".length(), longAction[0].length());
        assertEquals(75, longAction[1].length());
    }
}
