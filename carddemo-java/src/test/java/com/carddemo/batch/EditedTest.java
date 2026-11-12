package com.carddemo.batch;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Expected values are what GnuCOBOL's MOVE of a S9(9)V99 into each picture produces. */
class EditedTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0            |'         .00 '|'000000000.00 '",
            "-0.05        |'         .05-'|'000000000.05-'",
            "-1234.5      |'     1234.50-'|'000001234.50-'",
            "987654321.99 |'987654321.99 '|'987654321.99 '"})
    void statementAmountsEditLikeTheLegacyPictures(BigDecimal value, String zeroSuppressed, String zeroFilled) {
        assertThat(Edited.trailingMinus(value)).isEqualTo(zeroSuppressed);
        assertThat(Edited.zeroFilledTrailingMinus(value)).isEqualTo(zeroFilled);
    }
}
