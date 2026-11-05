package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.math.BigDecimal;
import java.util.Arrays;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.KeyedDataset;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;
import com.carddemo.records.Fields;

/** CBTRN02C: posts DALYTRAN into the transaction master, category balances and account cycles. Rejects go to DALYREJS. */
@Component
public final class Cbtrn02c implements BatchProgram {

    public static final String NAME = "CBTRN02C";

    private static final int TRAN = 350;
    private static final int ID = 0;
    private static final int TYPE = 16;
    private static final int CATEGORY = 18;
    private static final int SOURCE = 22;
    private static final int DESCRIPTION = 32;
    private static final int AMOUNT = 132;
    private static final int MERCHANT_ID = 143;
    private static final int MERCHANT_NAME = 152;
    private static final int MERCHANT_CITY = 202;
    private static final int MERCHANT_ZIP = 252;
    private static final int CARD = 262;
    private static final int ORIGIN = 278;
    private static final int PROCESSED = 304;
    private static final int BALANCE = 12;
    private static final int LIMIT = 24;
    private static final int EXPIRY = 58;
    private static final int CYCLE_CREDIT = 78;
    private static final int CYCLE_DEBIT = 90;
    private static final int XREF_ACCOUNT = 25;
    private static final int CATEGORY_BALANCE = 17;
    private static final int REJECT = 430;
    private static final int TRAILER = 80;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        step.display("START OF EXECUTION OF PROGRAM CBTRN02C");
        SequentialInput daily = step.input("DALYTRAN");
        KeyedDataset transactions = step.keyed("TRANFILE");
        KeyedDataset xref = step.keyed("XREFFILE");
        SequentialOutput rejects = step.output("DALYREJS");
        KeyedDataset accounts = step.keyed("ACCTFILE");
        KeyedDataset balances = step.keyed("TCATBALF");
        require(step, daily.open(), "ERROR OPENING DALYTRAN");
        require(step, transactions.open(OpenMode.OUTPUT), "ERROR OPENING TRANSACTION FILE");
        require(step, xref.open(OpenMode.INPUT), "ERROR OPENING CROSS REF FILE");
        require(step, rejects.open(OpenMode.OUTPUT), "ERROR OPENING DALY REJECTS FILE");
        require(step, accounts.open(OpenMode.I_O), "ERROR OPENING ACCOUNT MASTER FILE");
        require(step, balances.open(OpenMode.I_O), "ERROR OPENING TRANSACTION BALANCE FILE");

        int processed = 0;
        int rejected = 0;
        byte[] category = new byte[50];
        boolean eof = false;
        while (!eof) {
            Read read = daily.read();
            if (read.status() == FileStatus.END_OF_FILE) {
                eof = true;
            } else if (!(read instanceof Read.Found found) || found.status() != FileStatus.OK) {
                throw IoFailure.abend(step, "ERROR READING DALYTRAN FILE", read.status());
            } else {
                processed++;
                byte[] record = found.record();
                Checked checked = validate(step, xref, accounts, record);
                if (checked.reason() == 0) {
                    category = post(step, transactions, accounts, balances, record, checked, category);
                } else {
                    rejected++;
                    writeReject(step, rejects, record, checked);
                }
            }
        }

        require(step, daily.close(), "ERROR CLOSING DALYTRAN FILE");
        require(step, transactions.close(), "ERROR CLOSING TRANSACTION FILE");
        FileStatus xrefClosed = xref.close();
        require(step, xrefClosed, "ERROR CLOSING CROSS REF FILE");
        FileStatus rejectsClosed = rejects.close();
        if (rejectsClosed != FileStatus.OK) {
            // 9300 displays the cross-reference status, not the rejects status.
            throw IoFailure.abend(step, "ERROR CLOSING DAILY REJECTS FILE", xrefClosed);
        }
        require(step, accounts.close(), "ERROR CLOSING ACCOUNT FILE");
        require(step, balances.close(), "ERROR CLOSING TRANSACTION BALANCE FILE");
        step.display("TRANSACTIONS PROCESSED :" + "%09d".formatted(processed));
        step.display("TRANSACTIONS REJECTED  :" + "%09d".formatted(rejected));
        step.display("END OF EXECUTION OF PROGRAM CBTRN02C");
        return rejected > 0 ? 4 : 0;
    }

    private Checked validate(Step step, KeyedDataset xref, KeyedDataset accounts, byte[] daily) {
        byte[] key = Arrays.copyOfRange(daily, CARD, CARD + 16);
        Read cross = xref.read(key);
        if (!(cross instanceof Read.Found found)) {
            if (cross.status() != FileStatus.NOT_FOUND) {
                throw IoFailure.abend(step, "ERROR READING CROSS REF FILE", cross.status());
            }
            return new Checked(100, "INVALID CARD NUMBER FOUND", null, null);
        }
        byte[] accountKey = Arrays.copyOfRange(found.record(), XREF_ACCOUNT, XREF_ACCOUNT + 11);
        Read account = accounts.read(accountKey);
        if (!(account instanceof Read.Found held)) {
            if (account.status() != FileStatus.NOT_FOUND) {
                throw IoFailure.abend(step, "ERROR READING ACCOUNT FILE", account.status());
            }
            return new Checked(101, "ACCOUNT RECORD NOT FOUND", null, found.record());
        }
        BigDecimal projected = SignedMoney.store(SignedMoney.read(held.record(), CYCLE_CREDIT, 12)
                .subtract(SignedMoney.read(held.record(), CYCLE_DEBIT, 12))
                .add(SignedMoney.read(daily, AMOUNT, 11)), 9);
        Checked checked = new Checked(0, "", held.record(), found.record());
        if (SignedMoney.read(held.record(), LIMIT, 12).compareTo(projected) < 0) {
            checked = new Checked(102, "OVERLIMIT TRANSACTION", held.record(), found.record());
        }
        return rejectIfExpired(checked, held.record(), daily);
    }

    @LegacyDefect(id = "CBTRN02C-expired-overwrites-overlimit",
            goldens = {"posting-overlimit-and-expired-reports-103"},
            behavior = "the expiration check is not an ELSE of the limit check, so a transaction that is both over "
                    + "the credit limit and past the account expiration date is rejected as 103 instead of 102",
            fix = "keep 102 when the limit already failed")
    private static Checked rejectIfExpired(Checked checked, byte[] account, byte[] daily) {
        if (Fields.text(account, EXPIRY, 10).compareTo(Fields.text(daily, ORIGIN, 10)) < 0) {
            return new Checked(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION", account, checked.xref());
        }
        return checked;
    }

    private byte[] post(Step step, KeyedDataset transactions, KeyedDataset accounts, KeyedDataset balances,
            byte[] daily, Checked checked, byte[] category) {
        byte[] tran = new byte[TRAN];
        copy(daily, tran, ID, 16);
        copy(daily, tran, TYPE, 2);
        copy(daily, tran, CATEGORY, 4);
        copy(daily, tran, SOURCE, 10);
        copy(daily, tran, DESCRIPTION, 100);
        copy(daily, tran, AMOUNT, 11);
        copy(daily, tran, MERCHANT_ID, 9);
        copy(daily, tran, MERCHANT_NAME, 50);
        copy(daily, tran, MERCHANT_CITY, 50);
        copy(daily, tran, MERCHANT_ZIP, 10);
        copy(daily, tran, CARD, 16);
        copy(daily, tran, ORIGIN, 26);
        Fields.text(tran, PROCESSED, 26, Db2Timestamp.format(step.clock()));
        category = updateBalance(step, balances, daily, checked.xref(), category);
        updateAccount(accounts, daily, checked.account());
        require(step, transactions.write(tran), "ERROR WRITING TO TRANSACTION FILE");
        return category;
    }

    private byte[] updateBalance(Step step, KeyedDataset balances, byte[] daily, byte[] xref, byte[] category) {
        byte[] key = new byte[17];
        System.arraycopy(xref, XREF_ACCOUNT, key, 0, 11);
        System.arraycopy(daily, TYPE, key, 11, 2);
        System.arraycopy(daily, CATEGORY, key, 13, 4);
        Read read = balances.read(key);
        boolean create = read.status() == FileStatus.NOT_FOUND;
        if (read instanceof Read.Found found && found.status() == FileStatus.OK) {
            category = found.record();
        } else if (create) {
            step.display("TCATBAL record not found for key : " + new String(key, ISO_8859_1) + ".. Creating.");
        } else {
            throw IoFailure.abend(step, "ERROR READING TRANSACTION BALANCE FILE", read.status());
        }
        BigDecimal amount = SignedMoney.read(daily, AMOUNT, 11);
        if (create) {
            byte[] created = category.clone();
            System.arraycopy(key, 0, created, 0, key.length);
            SignedMoney.write(created, CATEGORY_BALANCE, 11, amount);
            require(step, balances.write(created), "ERROR WRITING TRANSACTION BALANCE FILE");
            return created;
        }
        byte[] updated = category.clone();
        SignedMoney.write(updated, CATEGORY_BALANCE, 11, SignedMoney.read(updated, CATEGORY_BALANCE, 11).add(amount));
        require(step, balances.rewrite(updated), "ERROR REWRITING TRANSACTION BALANCE FILE");
        return updated;
    }

    /** 2800 sets reason 109 on an invalid key and ignores every other status, then the transaction is still written. */
    private static void updateAccount(KeyedDataset accounts, byte[] daily, byte[] account) {
        BigDecimal amount = SignedMoney.read(daily, AMOUNT, 11);
        byte[] updated = account.clone();
        SignedMoney.write(updated, BALANCE, 12, SignedMoney.read(updated, BALANCE, 12).add(amount));
        int cycle = amount.signum() >= 0 ? CYCLE_CREDIT : CYCLE_DEBIT;
        SignedMoney.write(updated, cycle, 12, SignedMoney.read(updated, cycle, 12).add(amount));
        accounts.rewrite(updated);
    }

    private static void writeReject(Step step, SequentialOutput rejects, byte[] daily, Checked checked) {
        byte[] reject = new byte[REJECT];
        System.arraycopy(daily, 0, reject, 0, Math.min(daily.length, TRAN));
        Fields.text(reject, TRAN, 4, "%04d".formatted(checked.reason()));
        Fields.text(reject, TRAN + 4, TRAILER - 4, checked.description());
        require(step, rejects.write(reject), "ERROR WRITING TO REJECTS FILE");
    }

    private static void copy(byte[] from, byte[] to, int offset, int length) {
        System.arraycopy(from, offset, to, offset, length);
    }

    private static void require(Step step, FileStatus status, String message) {
        if (status != FileStatus.OK) {
            throw IoFailure.abend(step, message, status);
        }
    }

    private record Checked(int reason, String description, byte[] account, byte[] xref) {}
}
