package com.carddemo.vsam;

import com.carddemo.copybook.Field;
import com.carddemo.copybook.Pic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnTest {

    private static final Pic.Numeric SIGNED_MONEY = new Pic.Numeric(5, 2, true, Pic.Usage.DISPLAY);

    @ParameterizedTest
    @CsvSource({
            "0001{, 0.10",
            "0001A, 0.11",
            "0001I, 0.19",
            "0001}, -0.10",
            "0001J, -0.11",
            "0001R, -0.19",
            "00015, 0.15",
    })
    void decodesOverpunchedSigns(String raw, BigDecimal expected) {
        assertThat(Column.zoned(raw, SIGNED_MONEY)).isEqualTo(expected);
    }

    @Test
    void rejectsNegativeUnsignedAndNonNumericValues() {
        Pic.Numeric unsigned = new Pic.Numeric(5, 0, false, Pic.Usage.DISPLAY);
        assertThatThrownBy(() -> Column.zoned("0001}", unsigned)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Column.zoned("     ", SIGNED_MONEY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBothLegacyTimestampFormats() {
        Column ts = Column.of(new Field("TRAN-PROC-TS", 0, new Pic.Alphanumeric(26)), ColumnKind.TIMESTAMP);
        LocalDateTime expected = LocalDateTime.of(2022, 6, 10, 19, 27, 53, 120_000_000);

        assertThat(ts.decode("2022-06-10 19:27:53.120000")).isEqualTo(expected);
        assertThat(ts.decode("2022-06-10-19.27.53.120000")).isEqualTo(expected);
        assertThat(ts.decode(" ".repeat(26))).isNull();
    }
}
