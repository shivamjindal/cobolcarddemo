package com.carddemo.transactions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionEntryTest {

    private static final TransactionEntry.Reads ON_FILE = new TransactionEntry.Reads() {
        @Override
        public Optional<String> cardFor(long accountId) {
            return Optional.of("4111111111111111");
        }

        @Override
        public Optional<Long> accountFor(String cardNumber) {
            return Optional.of(1L);
        }
    };

    @Test
    void aDayThatIsNotOnTheCalendarIsRejected() {
        rejected(fields("+00000012.50", "2026-02-29", "2026-11-02"), "Orig Date - Not a valid date...", "TORIGDT");
    }

    @Test
    void aDateBeforeTheGregorianReformIsRejected() {
        rejected(fields("+00000012.50", "1582-10-14", "2026-11-02"), "Orig Date - Not a valid date...", "TORIGDT");
        rejected(fields("+00000012.50", "1582-10-15", "1582-10-14"), "Proc Date - Not a valid date...", "TPROCDT");
    }

    @Test
    void theGregorianStartIsARealDate() {
        TransactionEntry.Decision decision = TransactionEntry.check(
                fields("+00000012.50", "1582-10-15", "2026-11-02"), ON_FILE);

        assertThat(decision).isInstanceOfSatisfying(TransactionEntry.Accept.class, accept -> {
            assertThat(accept.draft().originated()).isEqualTo(LocalDate.of(1582, 10, 15));
            assertThat(accept.draft().processed()).isEqualTo(LocalDate.of(2026, 11, 2));
            assertThat(accept.draft().amount()).isEqualTo(new BigDecimal("12.50"));
            assertThat(accept.draft().amount().scale()).isEqualTo(2);
            assertThat(accept.draft().cardNumber()).isEqualTo("4111111111111111");
        });
    }

    @Test
    void aDateThatIsNotTenCharactersIsALayoutError() {
        rejected(fields("+00000012.50", "2026-1-01", "2026-11-02"),
                "Orig Date should be in format YYYY-MM-DD", "TORIGDT");
    }

    @Test
    void anAmountMustMatchTheSignedPicture() {
        rejected(fields("000000012.50", "2026-02-29", "2026-11-02"),
                "Amount should be in format -99999999.99", "TRNAMT");
        rejected(fields("+12.50", "2026-11-01", "2026-11-02"),
                "Amount should be in format -99999999.99", "TRNAMT");
        rejected(fields("+00000012.5", "2026-11-01", "2026-11-02"),
                "Amount should be in format -99999999.99", "TRNAMT");

        TransactionEntry.Decision decision = TransactionEntry.check(
                fields("-00000099.99", "2026-11-01", "2026-11-02"), ON_FILE);
        assertThat(decision).isInstanceOfSatisfying(TransactionEntry.Accept.class, accept -> {
            assertThat(accept.draft().amount()).isEqualTo(new BigDecimal("-99.99"));
            assertThat(accept.draft().amount().scale()).isEqualTo(2);
        });
    }

    private static void rejected(Map<String, String> fields, String message, String cursor) {
        assertThat(TransactionEntry.check(fields, ON_FILE))
                .isInstanceOfSatisfying(TransactionEntry.Reject.class, reject -> {
                    assertThat(reject.message()).isEqualTo(message);
                    assertThat(reject.cursor()).isEqualTo(cursor);
                });
    }

    private static Map<String, String> fields(String amount, String orig, String proc) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ACTIDIN", "00000000001");
        fields.put("TTYPCD", "01");
        fields.put("TCATCD", "0001");
        fields.put("TRNSRC", "POS TERM");
        fields.put("TDESC", "COFFEE SHOP");
        fields.put("TRNAMT", amount);
        fields.put("TORIGDT", orig);
        fields.put("TPROCDT", proc);
        fields.put("MID", "000012345");
        fields.put("MNAME", "JOES COFFEE");
        fields.put("MCITY", "SEATTLE");
        fields.put("MZIP", "98101");
        fields.put("CONFIRM", "Y");
        return fields;
    }
}
