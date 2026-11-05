package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.KeyedDataset;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;
import com.carddemo.records.Fields;

/** CBACT04C: one interest transaction per category balance whose disclosure rate is not zero. */
@Component
public final class Cbact04c implements BatchProgram {

    public static final String NAME = "CBACT04C";

    private static final BigDecimal PER_YEAR = new BigDecimal("1200");
    private static final int ACCOUNT = 11;
    private static final int BALANCE = 12;
    private static final int CYCLE_CREDIT = 78;
    private static final int CYCLE_DEBIT = 90;
    private static final int GROUP = 112;
    private static final int CATEGORY_BALANCE = 17;
    private static final int XREF_ACCOUNT = 25;
    private static final int RATE = 16;
    private static final int TRAN = 350;
    private static final String DESCRIPTION_PREFIX = "Int. for a/c ";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        step.display("START OF EXECUTION OF PROGRAM CBACT04C");
        KeyedDataset categories = step.keyed("TCATBALF");
        KeyedDataset xref = step.keyed("XREFFILE");
        KeyedDataset disclosure = step.keyed("DISCGRP");
        KeyedDataset accounts = step.keyed("ACCTFILE");
        SequentialOutput transactions = step.output("TRANSACT");
        require(step, categories.open(OpenMode.INPUT), "ERROR OPENING TRANSACTION CATEGORY BALANCE");
        FileStatus xrefOpened = xref.open(OpenMode.INPUT);
        if (xrefOpened != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR OPENING CROSS REF FILE" + xrefOpened.code(), xrefOpened);
        }
        require(step, disclosure.open(OpenMode.INPUT), "ERROR OPENING DALY REJECTS FILE");
        require(step, accounts.open(OpenMode.I_O), "ERROR OPENING ACCOUNT MASTER FILE");
        require(step, transactions.open(OpenMode.OUTPUT), "ERROR OPENING TRANSACTION FILE");

        String parmDate = (step.parm() + " ".repeat(10)).substring(0, 10);
        charge(step, categories, xref, disclosure, accounts, transactions, parmDate);

        require(step, categories.close(), "ERROR CLOSING TRANSACTION BALANCE FILE");
        require(step, xref.close(), "ERROR CLOSING CROSS REF FILE");
        require(step, disclosure.close(), "ERROR CLOSING DISCLOSURE GROUP FILE");
        require(step, accounts.close(), "ERROR CLOSING ACCOUNT FILE");
        require(step, transactions.close(), "ERROR CLOSING TRANSACTION FILE");
        step.display("END OF EXECUTION OF PROGRAM CBACT04C");
        return 0;
    }

    @LegacyDefect(id = "CBACT04C-skips-last-account",
            goldens = {"interest-default-group", "interest-group-rate-truncates", "interest-negative-balance",
                    "interest-zero-rate-no-transaction", "interest-unknown-group-uses-default", "interest-two-accounts"},
            behavior = "PERFORM UNTIL tests END-OF-FILE before the body, so the ELSE that rewrites the last account "
                    + "is dead. That account's interest is written and its balance and cycle totals are left as they were",
            fix = "rewrite the last account after the category file is exhausted")
    private void charge(Step step, KeyedDataset categories, KeyedDataset xref, KeyedDataset disclosure,
            KeyedDataset accounts, SequentialOutput transactions, String parmDate) {
        Run run = new Run();
        boolean eof = false;
        while (!eof) {
            if (!eof) {
                Read read = categories.readNext();
                if (read.status() == FileStatus.END_OF_FILE) {
                    eof = true;
                } else if (!(read instanceof Read.Found found) || found.status() != FileStatus.OK) {
                    throw IoFailure.abend(step, "ERROR READING TRANSACTION CATEGORY FILE", read.status());
                } else {
                    byte[] row = found.record();
                    step.display(new String(row, ISO_8859_1));
                    String accountId = Fields.text(row, 0, ACCOUNT);
                    if (!accountId.equals(run.lastAccount)) {
                        if (!run.first) {
                            rewriteAccount(step, accounts, run);
                        } else {
                            run.first = false;
                        }
                        run.total = BigDecimal.ZERO;
                        run.lastAccount = accountId;
                        run.account = readAccount(step, accounts, Arrays.copyOf(row, ACCOUNT));
                        run.card = readCard(step, xref, Arrays.copyOf(row, ACCOUNT));
                    }
                    BigDecimal rate = rate(step, disclosure, run.account, row);
                    if (rate.signum() != 0) {
                        BigDecimal monthly = SignedMoney.store(
                                SignedMoney.read(row, CATEGORY_BALANCE, 11).multiply(rate)
                                        .divide(PER_YEAR, 20, RoundingMode.DOWN), 9);
                        run.total = SignedMoney.store(run.total.add(monthly), 9);
                        writeTransaction(step, transactions, parmDate, run, monthly);
                    }
                }
            } else {
                // PERFORM UNTIL tests the flag before the body, so this ELSE is never entered.
                rewriteAccount(step, accounts, run);
            }
        }
    }

    private static void rewriteAccount(Step step, KeyedDataset accounts, Run run) {
        byte[] updated = run.account.clone();
        SignedMoney.write(updated, BALANCE, 12, SignedMoney.read(updated, BALANCE, 12).add(run.total));
        SignedMoney.write(updated, CYCLE_CREDIT, 12, BigDecimal.ZERO);
        SignedMoney.write(updated, CYCLE_DEBIT, 12, BigDecimal.ZERO);
        require(step, accounts.rewrite(updated), "ERROR RE-WRITING ACCOUNT FILE");
    }

    private static byte[] readAccount(Step step, KeyedDataset accounts, byte[] key) {
        Read read = accounts.read(key);
        if (read instanceof Read.Found found && found.status() == FileStatus.OK) {
            return found.record();
        }
        if (read.status() == FileStatus.NOT_FOUND) {
            step.display("ACCOUNT NOT FOUND: " + new String(key, ISO_8859_1));
        }
        throw IoFailure.abend(step, "ERROR READING ACCOUNT FILE", read.status());
    }

    private static byte[] readCard(Step step, KeyedDataset xref, byte[] accountKey) {
        Read read = xref.readAlternate(accountKey);
        if (read instanceof Read.Found found && found.status() == FileStatus.OK) {
            return Arrays.copyOf(found.record(), 16);
        }
        if (read.status() == FileStatus.NOT_FOUND) {
            step.display("ACCOUNT NOT FOUND: " + new String(accountKey, ISO_8859_1));
        }
        throw IoFailure.abend(step, "ERROR READING XREF FILE", read.status());
    }

    private static BigDecimal rate(Step step, KeyedDataset disclosure, byte[] account, byte[] row) {
        byte[] key = new byte[16];
        System.arraycopy(account, GROUP, key, 0, 10);
        System.arraycopy(row, ACCOUNT, key, 10, 6);
        Read read = disclosure.read(key);
        if (read instanceof Read.Found found && found.status() == FileStatus.OK) {
            return Fields.zoned(found.record(), RATE, 6, 2);
        }
        if (read.status() != FileStatus.NOT_FOUND) {
            throw IoFailure.abend(step, "ERROR READING DISCLOSURE GROUP FILE", read.status());
        }
        step.display("DISCLOSURE GROUP RECORD MISSING");
        step.display("TRY WITH DEFAULT GROUP CODE");
        Fields.text(key, 0, 10, "DEFAULT");
        Read fallback = disclosure.read(key);
        if (!(fallback instanceof Read.Found found) || found.status() != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR READING DEFAULT DISCLOSURE GROUP", fallback.status());
        }
        return Fields.zoned(found.record(), RATE, 6, 2);
    }

    private static void writeTransaction(Step step, SequentialOutput transactions, String parmDate, Run run,
            BigDecimal monthly) {
        run.suffix++;
        byte[] tran = new byte[TRAN];
        Fields.text(tran, 0, 16, parmDate + "%06d".formatted(run.suffix));
        Fields.text(tran, 16, 2, "01");
        Fields.zoned(tran, 18, 4, 0, false, BigDecimal.valueOf(5));
        Fields.text(tran, 22, 10, "System");
        byte[] description = (DESCRIPTION_PREFIX + Fields.text(run.account, 0, ACCOUNT)).getBytes(ISO_8859_1);
        System.arraycopy(description, 0, tran, 32, description.length);
        SignedMoney.write(tran, 132, 11, monthly);
        Fields.zoned(tran, 143, 9, 0, false, BigDecimal.ZERO);
        Fields.text(tran, 152, 50, "");
        Fields.text(tran, 202, 50, "");
        Fields.text(tran, 252, 10, "");
        System.arraycopy(run.card, 0, tran, 262, 16);
        String timestamp = Db2Timestamp.format(step.clock());
        Fields.text(tran, 278, 26, timestamp);
        Fields.text(tran, 304, 26, timestamp);
        require(step, transactions.write(tran), "ERROR WRITING TRANSACTION RECORD");
    }

    private static void require(Step step, FileStatus status, String message) {
        if (status != FileStatus.OK) {
            throw IoFailure.abend(step, message, status);
        }
    }

    private static final class Run {
        private byte[] account;
        private byte[] card;
        private BigDecimal total = BigDecimal.ZERO;
        private int suffix;
        private String lastAccount = " ".repeat(ACCOUNT);
        private boolean first = true;
    }
}
