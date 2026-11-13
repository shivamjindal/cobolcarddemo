package com.carddemo.inquiries;

import com.carddemo.accounts.Account;
import com.carddemo.mq.MqMessage;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.assertThat;

class AccountInquiryTest {

    private static final String QUEUE = "CARD.DEMO.REQUEST.ACCT";

    @Test
    void foundAccountIsTheLabeledPictureGroup() {
        Account account = new Account(1, "Y", new BigDecimal("-10.00"), new BigDecimal("1940.00"),
                new BigDecimal("0.00"), LocalDate.parse("2014-11-20"), null, LocalDate.parse("2025-05-20"),
                new BigDecimal("-0.05"), new BigDecimal("3.25"), "10001", "GRP");
        var puts = AccountInquiry.decide(request("INQA00000000001TRAILER", "REQ00001"), QUEUE,
                id -> id == 1L ? Optional.of(account) : Optional.empty());

        assertThat(puts).singleElement().satisfies(put -> {
            assertThat(put.queue()).isEqualTo(AccountInquiry.REPLY);
            assertThat(put.message().correlationId()).isEqualTo("REQ00001");
            assertThat(put.message().text()).isEqualTo("ACCOUNT ID : 00000000001"
                    + "ACCOUNT STATUS : Y"
                    + "BALANCE : 00000000100}"
                    + "CREDIT LIMIT : 000000194000"
                    + "CASH LIMIT : 000000000000"
                    + "OPEN DATE : 2014-11-20"
                    + "EXPR DATE :           "
                    + "REIS DATE : 2025-05-20"
                    + "CREDIT BAL : 00000000000N"
                    + "DEBIT BAL : 000000000325"
                    + "GROUP ID : GRP       ");
        });
    }

    @Test
    void zonedDecimalKeepsADigitWhenNonNegativeAndOverpunchesTheLastDigitWhenNegative() {
        assertThat(AccountInquiry.zoned(new BigDecimal("1940.00"))).isEqualTo("000000194000");
        assertThat(AccountInquiry.zoned(new BigDecimal("0.00"))).isEqualTo("000000000000");
        assertThat(AccountInquiry.zoned(new BigDecimal("-0.01"))).isEqualTo("00000000000J");
        assertThat(AccountInquiry.zoned(new BigDecimal("-0.09"))).isEqualTo("00000000000R");
    }

    @Test
    void missingAccountNamesTheIdAndStops() {
        var puts = AccountInquiry.decide(request("INQA00000000099", "REQ00002"), QUEUE, id -> Optional.empty());
        assertThat(puts).singleElement().satisfies(put -> {
            assertThat(put.queue()).isEqualTo(AccountInquiry.REPLY);
            assertThat(put.message().text()).isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 00000000099");
            assertThat(put.message().correlationId()).isEqualTo("REQ00002");
        });
    }

    @Test
    void wrongFunctionOrZeroKeyNamesBothFields() {
        Account present = new Account(1, "Y", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, "", "GROUP00001");
        LongFunction<Optional<Account>> onFile = id -> Optional.of(present);
        assertThat(AccountInquiry.decide(request("NOPE00000000001", "REQ00003"), QUEUE, onFile).getFirst().message().text())
                .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 00000000001FUNCTION : NOPE");
        assertThat(AccountInquiry.decide(request("INQA00000000000", "REQ00004"), QUEUE, onFile).getFirst().message().text())
                .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 00000000000FUNCTION : INQA");
        assertThat(AccountInquiry.decide(request("INQA", "REQ00006"), QUEUE, onFile).getFirst().message().text())
                .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID :            FUNCTION : INQA");
    }

    @Test
    void failedReadPutsTheTruncatedErrorAndKeepsTheRequestCorrelationId() {
        var puts = AccountInquiry.decide(request("INQA00000000001", "REQ00005"), QUEUE,
                id -> {
                    throw new DataRetrievalFailureException("ACCTDAT");
                });
        assertThat(puts).singleElement().satisfies(put -> {
            assertThat(put.queue()).isEqualTo(AccountInquiry.ERROR);
            assertThat(put.message().correlationId()).isEqualTo("REQ00005");
            assertThat(put.message().text()).isEqualTo(" ".repeat(27) + "ERROR WHILE READING ACCTF"
                    + "  17  00000  " + QUEUE + " ".repeat(48 - QUEUE.length()));
        });
    }

    private static MqMessage request(String text, String correlationId) {
        return new MqMessage(text, correlationId, "CARD.DEMO.REPLY.SHOULD.BE.IGNORED");
    }
}
