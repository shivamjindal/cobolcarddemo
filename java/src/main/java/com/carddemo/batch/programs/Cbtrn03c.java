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
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;
import com.carddemo.records.Fields;

/** CBTRN03C: the daily transaction report. Date-window and total defects are reproduced, each in its own method. */
@Component
public final class Cbtrn03c implements BatchProgram {

    public static final String NAME = "CBTRN03C";

    private static final int RECORD = 350;
    private static final int LINE = 133;
    private static final int PAGE = 20;
    private static final String INTEGER = "ZZZ,ZZZ,ZZZ";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        step.display("START OF EXECUTION OF PROGRAM CBTRN03C");
        SequentialInput transactions = openInput(step, "TRANFILE", "ERROR OPENING TRANFILE");
        SequentialOutput report = openOutput(step, "TRANREPT", "ERROR OPENING REPTFILE");
        KeyedDataset xref = openKeyed(step, "CARDXREF", "ERROR OPENING CROSS REF FILE");
        KeyedDataset types = openKeyed(step, "TRANTYPE", "ERROR OPENING TRANSACTION TYPE FILE");
        KeyedDataset categories = openKeyed(step, "TRANCATG", "ERROR OPENING TRANSACTION CATG FILE");
        SequentialInput dates = openInput(step, "DATEPARM", "ERROR OPENING DATE PARM FILE");
        Window window = readDates(step, dates);
        if (window.present()) {
            step.display("Reporting from " + window.start() + " to " + window.end());
            transactions(step, transactions, report, xref, types, categories, window);
        }
        closeInput(step, transactions, "ERROR CLOSING POSTED TRANSACTION FILE");
        closeOutput(step, report, "ERROR CLOSING REPORT FILE");
        closeKeyed(step, xref, "ERROR CLOSING CROSS REF FILE");
        closeKeyed(step, types, "ERROR CLOSING TRANSACTION TYPE FILE");
        closeKeyed(step, categories, "ERROR CLOSING TRANSACTION CATG FILE");
        closeInput(step, dates, "ERROR CLOSING DATE PARM FILE");
        step.display("END OF EXECUTION OF PROGRAM CBTRN03C");
        return 0;
    }

    private void transactions(Step step, SequentialInput transactions, SequentialOutput report, KeyedDataset xref,
            KeyedDataset types, KeyedDataset categories, Window window) {
        byte[] current = new byte[RECORD];
        boolean end = false;
        Totals totals = new Totals();
        while (!end) {
            Read read = transactions.read();
            if (read instanceof Read.Found found && found.status() == FileStatus.OK) {
                current = found.record();
            } else if (read.status() == FileStatus.END_OF_FILE) {
                end = true;
            } else {
                throw IoFailure.abend(step, "ERROR READING TRANSACTION FILE", read.status());
            }
            if (stopAtOutOfRangeDate(procDate(current), window.start(), window.end())) {
                return;
            }
            if (!end) {
                step.display(new String(current, ISO_8859_1));
                String card = text(current, 262, 16);
                if (!card.equals(totals.card)) {
                    if (!totals.first) {
                        writeAccountTotal(step, report, totals);
                    }
                    totals.card = card;
                    byte[] cross = lookup(step, xref, card.getBytes(ISO_8859_1), "INVALID CARD NUMBER : ");
                    totals.account = text(cross, 25, 11);
                }
                byte[] type = lookup(step, types, slice(current, 16, 2), "INVALID TRANSACTION TYPE : ");
                byte[] category = lookup(step, categories, slice(current, 16, 6), "INVALID TRAN CATG KEY : ");
                writeTransaction(step, report, totals, window, current, text(type, 2, 15), text(category, 6, 29));
            } else {
                endOfFile(step, report, totals, amount(current));
            }
        }
    }

    @LegacyDefect(id = "CBTRN03C-next-sentence-ends-run",
            goldens = {"tranrept-out-of-range-stops-run"},
            behavior = "NEXT SENTENCE on a process date outside DATEPARM leaves the PERFORM, so the run ends RC=0 "
                    + "without reading later records or writing totals",
            fix = "skip that record and keep reading")
    private static boolean stopAtOutOfRangeDate(String proc, String start, String end) {
        return proc.compareTo(start) < 0 || proc.compareTo(end) > 0;
    }

    @LegacyDefect(id = "CBTRN03C-drops-last-account-total",
            goldens = {"tranrept-two-cards"},
            behavior = "1120-WRITE-ACCOUNT-TOTALS runs only when the card changes, so the last card's account total "
                    + "is never written",
            fix = "write the account total for the current card before the page and grand totals at end of file")
    private void endOfFile(Step step, SequentialOutput report, Totals totals, BigDecimal amount) {
        step.display("TRAN-AMT " + displayed(amount));
        step.display("WS-PAGE-TOTAL" + displayed(totals.page));
        addStaleAmount(totals, amount);
        writePageTotal(step, report, totals);
        writeGrandTotal(step, report, totals);
    }

    @LegacyDefect(id = "CBTRN03C-eof-adds-stale-amount",
            goldens = {"tranrept-two-cards"},
            behavior = "at end of file TRAN-RECORD still holds the last transaction, and that amount is added to "
                    + "the page and account totals a second time",
            fix = "do not add TRAN-AMT on the end-of-file branch")
    private static void addStaleAmount(Totals totals, BigDecimal amount) {
        totals.page = totals.page.add(amount);
        totals.accountTotal = totals.accountTotal.add(amount);
    }

    private void writeTransaction(Step step, SequentialOutput report, Totals totals, Window window, byte[] record,
            String typeDescription, String categoryDescription) {
        if (totals.first) {
            totals.first = false;
            writeHeaders(step, report, totals, window);
        }
        if (totals.lines % PAGE == 0) {
            writePageTotal(step, report, totals);
            writeHeaders(step, report, totals, window);
        }
        BigDecimal amount = amount(record);
        totals.page = totals.page.add(amount);
        totals.accountTotal = totals.accountTotal.add(amount);
        byte[] line = spaces();
        text(line, 0, 16, text(record, 0, 16));
        text(line, 17, 11, totals.account);
        text(line, 29, 2, text(record, 16, 2));
        text(line, 31, 1, "-");
        text(line, 32, 15, typeDescription);
        text(line, 48, 4, text(record, 18, 4));
        text(line, 52, 1, "-");
        text(line, 53, 29, categoryDescription);
        text(line, 83, 10, text(record, 22, 10));
        text(line, 97, 15, edited(amount, ' '));
        writeLine(step, report, line);
        totals.lines++;
    }

    private void writeHeaders(Step step, SequentialOutput report, Totals totals, Window window) {
        byte[] name = spaces();
        text(name, 0, 38, "DALYREPT");
        text(name, 38, 41, "Daily Transaction Report");
        text(name, 79, 12, "Date Range: ");
        text(name, 91, 10, window.start());
        text(name, 101, 4, " to ");
        text(name, 105, 10, window.end());
        writeLine(step, report, name);
        totals.lines++;
        writeLine(step, report, spaces());
        totals.lines++;
        byte[] columns = spaces();
        text(columns, 0, 17, "Transaction ID");
        text(columns, 17, 12, "Account ID");
        text(columns, 29, 19, "Transaction Type");
        text(columns, 48, 35, "Tran Category");
        text(columns, 83, 14, "Tran Source");
        text(columns, 98, 16, "        Amount");
        writeLine(step, report, columns);
        totals.lines++;
        writeLine(step, report, dashes());
        totals.lines++;
    }

    private void writePageTotal(Step step, SequentialOutput report, Totals totals) {
        writeTotal(step, report, "Page Total", 11, 86, totals.page);
        totals.grand = totals.grand.add(totals.page);
        totals.page = BigDecimal.ZERO.setScale(2);
        totals.lines++;
        writeLine(step, report, dashes());
        totals.lines++;
    }

    private void writeAccountTotal(Step step, SequentialOutput report, Totals totals) {
        writeTotal(step, report, "Account Total", 13, 84, totals.accountTotal);
        totals.accountTotal = BigDecimal.ZERO.setScale(2);
        totals.lines++;
        writeLine(step, report, dashes());
        totals.lines++;
    }

    private void writeGrandTotal(Step step, SequentialOutput report, Totals totals) {
        writeTotal(step, report, "Grand Total", 11, 86, totals.grand);
    }

    private void writeTotal(Step step, SequentialOutput report, String label, int labelLength, int dots,
            BigDecimal amount) {
        byte[] line = spaces();
        text(line, 0, labelLength, label);
        Arrays.fill(line, labelLength, labelLength + dots, (byte) '.');
        text(line, labelLength + dots, 15, edited(amount, '+'));
        writeLine(step, report, line);
    }

    private static Window readDates(Step step, SequentialInput dates) {
        Read read = dates.read();
        if (read.status() == FileStatus.END_OF_FILE) {
            return Window.missing();
        }
        if (!(read instanceof Read.Found found) || found.status() != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR READING DATEPARM FILE", read.status());
        }
        String text = (new String(found.record(), ISO_8859_1) + " ".repeat(21)).substring(0, 21);
        return new Window(true, text.substring(0, 10), text.substring(11, 21));
    }

    private static byte[] lookup(Step step, KeyedDataset file, byte[] key, String message) {
        Read read = file.read(key);
        if (read instanceof Read.Found found && found.status() == FileStatus.OK) {
            return found.record();
        }
        step.display(message + new String(key, ISO_8859_1));
        IoFailure.displayIoStatus(step, "23");
        throw IoFailure.abendProgram(step);
    }

    private static SequentialInput openInput(Step step, String dd, String message) {
        SequentialInput input = step.input(dd);
        FileStatus opened = input.open();
        if (opened != FileStatus.OK) {
            throw IoFailure.abend(step, message, opened);
        }
        return input;
    }

    private static SequentialOutput openOutput(Step step, String dd, String message) {
        SequentialOutput output = step.output(dd);
        FileStatus opened = output.open(OpenMode.OUTPUT);
        if (opened != FileStatus.OK) {
            throw IoFailure.abend(step, message, opened);
        }
        return output;
    }

    private static KeyedDataset openKeyed(Step step, String dd, String message) {
        KeyedDataset file = step.keyed(dd);
        FileStatus opened = file.open(OpenMode.INPUT);
        if (opened != FileStatus.OK) {
            throw IoFailure.abend(step, message, opened);
        }
        return file;
    }

    private static void closeInput(Step step, SequentialInput input, String message) {
        FileStatus closed = input.close();
        if (closed != FileStatus.OK) {
            throw IoFailure.abend(step, message, closed);
        }
    }

    private static void closeOutput(Step step, SequentialOutput output, String message) {
        FileStatus closed = output.close();
        if (closed != FileStatus.OK) {
            throw IoFailure.abend(step, message, closed);
        }
    }

    private static void closeKeyed(Step step, KeyedDataset file, String message) {
        FileStatus closed = file.close();
        if (closed != FileStatus.OK) {
            throw IoFailure.abend(step, message, closed);
        }
    }

    private static void writeLine(Step step, SequentialOutput report, byte[] line) {
        FileStatus written = report.write(line);
        if (written != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR WRITING REPTFILE", written);
        }
    }

    private static String procDate(byte[] record) {
        return record.length < 314 ? "" : text(record, 304, 10);
    }

    private static BigDecimal amount(byte[] record) {
        return Fields.zoned(record, 132, 11, 2);
    }

    /** DISPLAY of PIC S9(09)V99: eleven digits and a trailing sign. */
    private static String displayed(BigDecimal amount) {
        long unscaled = amount.movePointRight(2).setScale(0, RoundingMode.DOWN).longValue();
        return "%011d".formatted(Math.abs(unscaled)) + (unscaled < 0 ? "-" : "+");
    }

    /** PIC -ZZZ,ZZZ,ZZZ.ZZ or +ZZZ,ZZZ,ZZZ.ZZ. The sign column is fixed and leading zeros print as spaces. */
    private static String edited(BigDecimal amount, char positive) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.DOWN);
        long unscaled = Math.abs(scaled.unscaledValue().longValue());
        String digits = "%09d".formatted(unscaled / 100);
        StringBuilder edited = new StringBuilder().append(scaled.signum() < 0 ? '-' : positive);
        boolean suppressing = true;
        int next = 0;
        for (int i = 0; i < INTEGER.length(); i++) {
            if (INTEGER.charAt(i) == ',') {
                edited.append(suppressing ? ' ' : ',');
            } else {
                char digit = digits.charAt(next++);
                suppressing &= digit == '0';
                edited.append(suppressing ? ' ' : digit);
            }
        }
        return edited.append('.').append("%02d".formatted(unscaled % 100)).toString();
    }

    private static byte[] spaces() {
        return Fields.spaces(LINE);
    }

    private static byte[] dashes() {
        byte[] line = new byte[LINE];
        Arrays.fill(line, (byte) '-');
        return line;
    }

    private static void text(byte[] record, int offset, int length, String value) {
        Fields.text(record, offset, length, value);
    }

    private static String text(byte[] record, int offset, int length) {
        return Fields.text(record, offset, length);
    }

    private static byte[] slice(byte[] record, int offset, int length) {
        return Arrays.copyOfRange(record, offset, offset + length);
    }

    private record Window(boolean present, String start, String end) {
        static Window missing() {
            return new Window(false, "", "");
        }
    }

    private static final class Totals {
        private boolean first = true;
        private int lines;
        private String card = " ".repeat(16);
        private String account = "";
        private BigDecimal page = BigDecimal.ZERO.setScale(2);
        private BigDecimal accountTotal = BigDecimal.ZERO.setScale(2);
        private BigDecimal grand = BigDecimal.ZERO.setScale(2);
    }
}
