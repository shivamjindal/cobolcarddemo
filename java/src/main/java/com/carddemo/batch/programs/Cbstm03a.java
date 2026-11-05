package com.carddemo.batch.programs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.batch.step.Abend;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;
import com.carddemo.records.Fields;

/**
 * CBSTM03A: one text statement and one HTML statement per cross-reference card, from the transaction table
 * loaded before any statement is written. The TIOT walk at the top of the COBOL is compiled out.
 */
@Component
public final class Cbstm03a implements BatchProgram {

    public static final String NAME = "CBSTM03A";

    private static final int TEXT = 80;
    private static final int HTML = 100;
    private static final int CARD = 16;
    private static final int TRAN = 350;
    private static final String RULE = "-".repeat(TEXT);
    private static final String START = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);
    private static final String END = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    private static final String TABLE = "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
    private static final String BLUE = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";
    private static final String ORANGE = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";
    private static final String GREY = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
    private static final String TEAL = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";
    private static final String GREEN_ID = "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String GREEN_DETAIL = "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String GREEN_AMOUNT = "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
    private static final String ROW_ID = "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String ROW_DETAIL = "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String ROW_AMOUNT = "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";

    private record CardGroup(String card, List<byte[]> transactions) {}

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        SequentialOutput text = step.output("STMTFILE");
        SequentialOutput html = step.output("HTMLFILE");
        text.open(OpenMode.OUTPUT);
        html.open(OpenMode.OUTPUT);

        Cbstm03b files = new Cbstm03b();
        opened(step, files, Cbstm03b.TRNXFILE);
        Cbstm03b.Reply first = files.call(step, Cbstm03b.TRNXFILE, 'R', null);
        if (!first.okOrWarning()) {
            throw failed(step, "ERROR READING TRNXFILE", first.status());
        }
        List<CardGroup> groups = load(step, files, first.record());
        opened(step, files, Cbstm03b.XREFFILE);
        opened(step, files, Cbstm03b.CUSTFILE);
        opened(step, files, Cbstm03b.ACCTFILE);

        boolean end = false;
        while (!end) {
            Cbstm03b.Reply xref = files.call(step, Cbstm03b.XREFFILE, 'R', null);
            if (xref.is("10")) {
                end = true;
            } else if (!xref.is("00")) {
                throw failed(step, "ERROR READING XREFFILE", xref.status());
            } else {
                byte[] xrefRecord = xref.record();
                Cbstm03b.Reply customer = files.call(step, Cbstm03b.CUSTFILE, 'K',
                        Arrays.copyOfRange(xrefRecord, 16, 25));
                if (!customer.is("00")) {
                    throw failed(step, "ERROR READING CUSTFILE", customer.status());
                }
                Cbstm03b.Reply account = files.call(step, Cbstm03b.ACCTFILE, 'K',
                        Arrays.copyOfRange(xrefRecord, 25, 36));
                if (!account.is("00")) {
                    throw failed(step, "ERROR READING ACCTFILE", account.status());
                }
                writeStatement(text, html, customer.record(), account.record(), xrefRecord, groups);
            }
        }

        closed(step, files, Cbstm03b.TRNXFILE);
        closed(step, files, Cbstm03b.XREFFILE);
        closed(step, files, Cbstm03b.CUSTFILE);
        closed(step, files, Cbstm03b.ACCTFILE);
        text.close();
        html.close();
        return 0;
    }

    private static List<CardGroup> load(Step step, Cbstm03b files, byte[] first) {
        List<CardGroup> groups = new ArrayList<>();
        byte[] current = first;
        String saved = cardOf(current);
        while (true) {
            String card = cardOf(current);
            if (groups.isEmpty() || !card.equals(saved)) {
                groups.add(new CardGroup(card, new ArrayList<>()));
                saved = card;
            }
            groups.getLast().transactions().add(Arrays.copyOf(current, TRAN));
            Cbstm03b.Reply next = files.call(step, Cbstm03b.TRNXFILE, 'R', null);
            if (next.is("00")) {
                current = next.record();
            } else if (next.is("10")) {
                return groups;
            } else {
                throw failed(step, "ERROR READING TRNXFILE", next.status());
            }
        }
    }

    private static void writeStatement(SequentialOutput text, SequentialOutput html, byte[] customer, byte[] account,
            byte[] xref, List<CardGroup> groups) {
        String accountId = Fields.text(account, 0, 11);
        BigDecimal balance = Fields.zoned(account, 12, 12, 2);
        String fico = Fields.text(customer, 329, 3);
        String name = firstWord(Fields.text(customer, 9, 25)) + " " + firstWord(Fields.text(customer, 34, 25)) + " "
                + firstWord(Fields.text(customer, 59, 25)) + " ";
        String address1 = Fields.text(customer, 84, 50);
        String address2 = Fields.text(customer, 134, 50);
        String cityLine = firstWord(Fields.text(customer, 184, 50)) + " " + firstWord(Fields.text(customer, 234, 2))
                + " " + firstWord(Fields.text(customer, 236, 3)) + " " + firstWord(Fields.text(customer, 239, 10)) + " ";

        text(text, START);
        htmlHeader(html, accountId);
        htmlIdentity(html, name, address1, address2, cityLine, accountId, balance, fico);
        text(text, pad(name, 75));
        text(text, address1);
        text(text, address2);
        text(text, cityLine);
        text(text, RULE);
        text(text, " ".repeat(33) + "Basic Details" + " ".repeat(34));
        text(text, RULE);
        text(text, "Account ID         :" + pad(accountId, 20));
        text(text, "Current Balance    :" + editNines(balance));
        text(text, "FICO Score         :" + pad(fico, 20));
        text(text, RULE);
        text(text, " ".repeat(30) + "TRANSACTION SUMMARY ");
        text(text, RULE);
        text(text, "Tran ID         " + pad("Tran Details    ", 51) + "  Tran Amount");
        text(text, RULE);

        BigDecimal total = BigDecimal.ZERO;
        String xrefCard = Fields.text(xref, 0, CARD);
        for (CardGroup group : groups) {
            if (group.card().compareTo(xrefCard) > 0) {
                break;
            }
            if (!group.card().equals(xrefCard)) {
                continue;
            }
            for (byte[] transaction : group.transactions()) {
                String id = Fields.text(transaction, CARD, CARD);
                String detail = Fields.text(transaction, 48, 49);
                BigDecimal amount = Fields.zoned(transaction, 148, 11, 2);
                text(text, id + " " + detail + "$" + editZ(amount));
                htmlTransaction(html, id, detail, editZ(amount));
                total = total.add(amount);
            }
        }
        text(text, RULE);
        text(text, "Total EXP:" + " ".repeat(56) + "$" + editZ(total));
        text(text, END);
        html(html, "<tr>");
        html(html, BLUE);
        html(html, "<h3>End of Statement</h3>");
        html(html, "</td>");
        html(html, "</tr>");
        html(html, "</table>");
        html(html, "</body>");
        html(html, "</html>");
    }

    private static void htmlHeader(SequentialOutput html, String accountId) {
        for (String line : List.of("<!DOCTYPE html>", "<html lang=\"en\">", "<head>", "<meta charset=\"utf-8\">",
                "<title>HTML Table Layout</title>", "</head>", "<body style=\"margin:0px;\">", TABLE, "<tr>", BLUE)) {
            html(html, line);
        }
        html(html, "<h3>Statement for Account Number: " + pad(accountId, 20) + "</h3>");
        html(html, "</td>");
        html(html, "</tr>");
        html(html, "<tr>");
        html(html, ORANGE);
        html(html, "<p style=\"font-size:16px\">Bank of XYZ</p>");
        html(html, "<p>410 Terry Ave N</p>");
        html(html, "<p>Seattle WA 99999</p>");
        html(html, "</td>");
        html(html, "</tr>");
        html(html, "<tr>");
        html(html, GREY);
    }

    private static void htmlIdentity(SequentialOutput html, String name, String address1, String address2,
            String cityLine, String accountId, BigDecimal balance, String fico) {
        html(html, "<p style=\"font-size:16px\">" + beforeTwoSpaces(pad(name, 75)) + "  </p>");
        html(html, "<p>" + beforeTwoSpaces(address1) + "  </p>");
        html(html, "<p>" + beforeTwoSpaces(address2) + "  </p>");
        html(html, "<p>" + beforeTwoSpaces(pad(cityLine, TEXT)) + "  </p>");
        html(html, "</td>");
        html(html, "</tr>");
        html(html, "<tr>");
        html(html, TEAL);
        html(html, "<p style=\"font-size:16px\">Basic Details</p>");
        html(html, "</td>");
        html(html, "</tr>");
        html(html, "<tr>");
        html(html, GREY);
        html(html, "<p>Account ID         : " + pad(accountId, 20) + "</p>");
        html(html, "<p>Current Balance    : " + editNines(balance) + "</p>");
        html(html, "<p>FICO Score         : " + pad(fico, 20) + "</p>");
        html(html, "</td>");
        html(html, "</tr>");
        html(html, "<tr>");
        html(html, TEAL);
        html(html, "<p style=\"font-size:16px\">Transaction Summary</p>");
        html(html, "</td>");
        html(html, "</tr>");
        html(html, "<tr>");
        html(html, GREEN_ID);
        html(html, "<p style=\"font-size:16px\">Tran ID</p>");
        html(html, "</td>");
        html(html, GREEN_DETAIL);
        html(html, "<p style=\"font-size:16px\">Tran Details</p>");
        html(html, "</td>");
        html(html, GREEN_AMOUNT);
        html(html, "<p style=\"font-size:16px\">Amount</p>");
        html(html, "</td>");
        html(html, "</tr>");
    }

    private static void htmlTransaction(SequentialOutput html, String id, String detail, String amount) {
        html(html, "<tr>");
        html(html, ROW_ID);
        html(html, "<p>" + id + "</p>");
        html(html, "</td>");
        html(html, ROW_DETAIL);
        html(html, "<p>" + detail + "</p>");
        html(html, "</td>");
        html(html, ROW_AMOUNT);
        html(html, "<p>" + amount + "</p>");
        html(html, "</td>");
        html(html, "</tr>");
    }

    @LegacyDefect(id = "CBSTM03A-city-cut-at-first-space",
            goldens = {"statement-two-accounts"},
            behavior = "the address line STRINGs city, state, country and zip DELIMITED BY one space, so a city "
                    + "with an embedded space such as West Bernita is printed as its first word",
            fix = "move the city, state, country and zip fields whole, the way address lines 1 and 2 are moved")
    private static String firstWord(String field) {
        int space = field.indexOf(' ');
        return space < 0 ? field : field.substring(0, space);
    }

    private static String beforeTwoSpaces(String field) {
        int at = field.indexOf("  ");
        return at < 0 ? field : field.substring(0, at);
    }

    /** PIC 9(9).99- : nine zero-padded integer digits, a point, two decimals, and a trailing blank or minus. */
    private static String editNines(BigDecimal value) {
        String digits = digits(value, 11, 2);
        return digits.substring(0, 9) + "." + digits.substring(9) + (value.signum() < 0 ? "-" : " ");
    }

    /** PIC Z(9).99- : the integer is zero-suppressed, and a zero integer leaves the decimal point in place. */
    private static String editZ(BigDecimal value) {
        String unscaled = digits(value, 11, 2);
        String integer = unscaled.substring(0, 9).replaceFirst("^0+", "");
        return " ".repeat(9 - integer.length()) + integer + "." + unscaled.substring(9)
                + (value.signum() < 0 ? "-" : " ");
    }

    private static String digits(BigDecimal value, int places, int scale) {
        String text = value.abs().movePointRight(scale).toBigInteger().toString();
        if (text.length() > places) {
            text = text.substring(text.length() - places);
        }
        return "0".repeat(places - text.length()) + text;
    }

    private static String pad(String value, int length) {
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        return value + " ".repeat(length - value.length());
    }

    private static String cardOf(byte[] record) {
        return Fields.text(record, 0, CARD);
    }

    private static void text(SequentialOutput out, String line) {
        out.write(fixed(TEXT, line));
    }

    private static void html(SequentialOutput out, String line) {
        out.write(fixed(HTML, line));
    }

    private static byte[] fixed(int length, String line) {
        byte[] record = Fields.spaces(length);
        Fields.text(record, 0, length, line);
        return record;
    }

    private static void opened(Step step, Cbstm03b files, String dd) {
        Cbstm03b.Reply reply = files.call(step, dd, 'O', null);
        if (!reply.okOrWarning()) {
            throw failed(step, "ERROR OPENING " + dd, reply.status());
        }
    }

    private static void closed(Step step, Cbstm03b files, String dd) {
        Cbstm03b.Reply reply = files.call(step, dd, 'C', null);
        if (!reply.okOrWarning()) {
            throw failed(step, "ERROR CLOSING " + dd, reply.status());
        }
    }

    private static Abend failed(Step step, String message, String status) {
        step.display(message);
        step.display("RETURN CODE: " + status);
        step.display("ABENDING PROGRAM");
        return Abend.withoutCode();
    }
}
