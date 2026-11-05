package com.carddemo.vsammq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountKeyTest {

    @ParameterizedTest(name = "[{0}] > ZEROES is {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "00000000001|true",
        "00000000000|false",
        "0000000000A|true",
        "0000000000J|true",
        "LID REQUEST|true",
        "abcdefghijk|true",
        "\" 0000000001\"|false",
        "\"0000000001 \"|false",
        "\"1          \"|false",
        "\"000000000 1\"|false",
        "00000000/00|false",
        "0000000001/|true",
        "1//////////|true",
        "1**********|true",
        "1%%%%%%%%%%|false",
        "0000000001&|false",
        "0000000001)|true",
    })
    void comparesAsGnuCobolWeighsEachByteAgainstZero(String raw, boolean positive) {
        assertEquals(positive, AccountKey.positive(raw));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "00000000001|1",
        "99999999999|99999999999",
        "0000000000A|-1",
        "\" 0000000001\"|-1",
    })
    void onlyElevenDigitsNameARecord(String raw, long id) {
        assertEquals(id, AccountKey.recordId(raw));
    }

    @Test
    void lowValuesCountBelowZero() {
        assertFalse(AccountKey.positive("\0".repeat(11)));
    }
}
