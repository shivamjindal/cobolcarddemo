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
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;
import com.carddemo.records.Fields;

/** CBACT01C: reads ACCTFILE and writes the fixed, array and variable-length extracts. */
@Component
public final class Cbact01c implements BatchProgram {

    public static final String NAME = "CBACT01C";

    private static final int OUT_LEN = 107;
    private static final int ARRAY_LEN = 110;
    private static final int AMOUNT = 12;
    private static final int PACKED_DEBIT = 90;
    private static final BigDecimal STUCK_DEBIT = new BigDecimal("2525.00");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        step.display("START OF EXECUTION OF PROGRAM CBACT01C");
        KeyedDataset accounts = step.keyed("ACCTFILE");
        FileStatus opened = accounts.open(OpenMode.INPUT);
        if (opened != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR OPENING ACCTFILE", opened);
        }
        SequentialOutput out = step.output("OUTFILE");
        SequentialOutput array = step.output("ARRYFILE");
        SequentialOutput variable = step.output("VBRCFILE");
        openOut(step, out, "ERROR OPENING OUTFILE");
        openOut(step, array, "ERROR OPENING ARRAYFILE");
        openOut(step, variable, "ERROR OPENING VBRC FILE");

        byte[] outRecord = new byte[OUT_LEN];
        for (Read read = accounts.readNext();; read = accounts.readNext()) {
            if (read.status() == FileStatus.END_OF_FILE) {
                break;
            }
            if (!(read instanceof Read.Found found) || found.status() != FileStatus.OK) {
                throw IoFailure.abend(step, "ERROR READING ACCOUNT FILE", read.status());
            }
            byte[] account = found.record();
            displayFields(step, account);
            wrote(step, out.write(outRecord(outRecord, account)));
            wrote(step, array.write(arrayRecord(account)));
            byte[] rec1 = variableRecord1(account);
            byte[] rec2 = variableRecord2(account);
            step.display("VBRC-REC1:" + new String(rec1, ISO_8859_1));
            step.display("VBRC-REC2:" + new String(rec2, ISO_8859_1));
            wrote(step, variable.write(rec1));
            wrote(step, variable.write(rec2));
            step.display(new String(account, ISO_8859_1));
        }

        FileStatus closed = accounts.close();
        if (closed != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR CLOSING ACCOUNT FILE", closed);
        }
        step.display("END OF EXECUTION OF PROGRAM CBACT01C");
        return 0;
    }

    private static void displayFields(Step step, byte[] account) {
        step.display("ACCT-ID                 :" + Fields.text(account, 0, 11));
        step.display("ACCT-ACTIVE-STATUS      :" + Fields.text(account, 11, 1));
        step.display("ACCT-CURR-BAL           :" + signed(account, 12));
        step.display("ACCT-CREDIT-LIMIT       :" + signed(account, 24));
        step.display("ACCT-CASH-CREDIT-LIMIT  :" + signed(account, 36));
        step.display("ACCT-OPEN-DATE          :" + Fields.text(account, 48, 10));
        step.display("ACCT-EXPIRAION-DATE     :" + Fields.text(account, 58, 10));
        step.display("ACCT-REISSUE-DATE       :" + Fields.text(account, 68, 10));
        step.display("ACCT-CURR-CYC-CREDIT    :" + signed(account, 78));
        step.display("ACCT-CURR-CYC-DEBIT     :" + signed(account, 90));
        step.display("ACCT-GROUP-ID           :" + Fields.text(account, 112, 10));
        step.display("-------------------------------------------------");
    }

    /** Elementary DISPLAY of S9(10)V99: twelve digits, no decimal point, and a separate sign. */
    private static String signed(byte[] record, int offset) {
        BigDecimal value = Fields.zoned(record, offset, AMOUNT, 2);
        return digits(value, AMOUNT, 2) + (value.signum() < 0 ? "-" : "+");
    }

    private static byte[] outRecord(byte[] out, byte[] account) {
        System.arraycopy(account, 0, out, 0, 68);
        System.arraycopy(reissueDate(Fields.text(account, 68, 10)), 0, out, 68, 10);
        System.arraycopy(account, 78, out, 78, AMOUNT);
        carryCycleDebit(out, account);
        System.arraycopy(account, 112, out, 97, 10);
        return out.clone();
    }

    @LegacyDefect(id = "CBACT01C-reissue-keeps-null-padding",
            goldens = {"acct-extract-two-accounts"},
            behavior = "COBDATFT writes eight bytes of YYYYMMDD into a twenty-byte area that starts as low-values, "
                    + "and the MOVE into OUT-ACCT-REISSUE-DATE keeps the two nulls after them",
            fix = "space-fill OUT-ACCT-REISSUE-DATE and move only the eight converted characters")
    private static byte[] reissueDate(String yyyyMmDd) {
        byte[] converted = new byte[10];
        byte[] date = yyyyMmDd.getBytes(ISO_8859_1);
        System.arraycopy(date, 0, converted, 0, 4);
        System.arraycopy(date, 5, converted, 4, 2);
        System.arraycopy(date, 8, converted, 6, 2);
        return converted;
    }

    @LegacyDefect(id = "CBACT01C-zero-cycle-debit-becomes-2525",
            goldens = {"acct-extract-two-accounts"},
            behavior = "1300-POPUL-ACCT-RECORD moves 2525.00 into the packed cycle debit only when the account "
                    + "debit is zero, so a later non-zero debit keeps the previous record's value",
            fix = "move ACCT-CURR-CYC-DEBIT into OUT-ACCT-CURR-CYC-DEBIT on every account")
    private static void carryCycleDebit(byte[] out, byte[] account) {
        if (Fields.zoned(account, 90, AMOUNT, 2).signum() == 0) {
            Fields.packed(out, PACKED_DEBIT, AMOUNT, 2, true, STUCK_DEBIT);
        }
    }

    private static byte[] arrayRecord(byte[] account) {
        byte[] array = initializedArray();
        System.arraycopy(account, 0, array, 0, 11);
        System.arraycopy(account, 12, array, 11, AMOUNT);
        Fields.packed(array, 23, AMOUNT, 2, true, new BigDecimal("1005.00"));
        System.arraycopy(account, 12, array, 30, AMOUNT);
        Fields.packed(array, 42, AMOUNT, 2, true, new BigDecimal("1525.00"));
        Fields.zoned(array, 49, AMOUNT, 2, true, new BigDecimal("-1025.00"));
        Fields.packed(array, 61, AMOUNT, 2, true, new BigDecimal("-2500.00"));
        return array;
    }

    /** INITIALIZE of a signed DISPLAY amount is digit zeros. A MOVE of zero overpunches the sign. */
    private static byte[] initializedArray() {
        byte[] array = new byte[ARRAY_LEN];
        Arrays.fill(array, 0, 11, (byte) '0');
        for (int slot = 0; slot < 5; slot++) {
            int at = 11 + slot * 19;
            Arrays.fill(array, at, at + AMOUNT, (byte) '0');
            Fields.packed(array, at + AMOUNT, AMOUNT, 2, true, BigDecimal.ZERO);
        }
        Arrays.fill(array, 106, ARRAY_LEN, (byte) ' ');
        return array;
    }

    private static byte[] variableRecord1(byte[] account) {
        return Arrays.copyOfRange(account, 0, 12);
    }

    private static byte[] variableRecord2(byte[] account) {
        byte[] record = new byte[39];
        System.arraycopy(account, 0, record, 0, 11);
        System.arraycopy(account, 12, record, 11, AMOUNT);
        System.arraycopy(account, 24, record, 23, AMOUNT);
        System.arraycopy(account, 68, record, 35, 4);
        return record;
    }

    private static String digits(BigDecimal value, int places, int scale) {
        String text = value.abs().movePointRight(scale).toBigInteger().toString();
        if (text.length() > places) {
            text = text.substring(text.length() - places);
        }
        return "0".repeat(places - text.length()) + text;
    }

    private static void openOut(Step step, SequentialOutput out, String message) {
        FileStatus status = out.open(OpenMode.OUTPUT);
        if (status != FileStatus.OK) {
            step.display(message + status.code());
            IoFailure.displayIoStatus(step, status.code());
            throw IoFailure.abendProgram(step);
        }
    }

    private static void wrote(Step step, FileStatus status) {
        if (status != FileStatus.OK && status != FileStatus.END_OF_FILE) {
            step.display("ACCOUNT FILE WRITE STATUS IS:" + status.code());
            IoFailure.displayIoStatus(step, status.code());
            throw IoFailure.abendProgram(step);
        }
    }
}
