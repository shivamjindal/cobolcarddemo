package com.carddemo.batch;

import com.carddemo.accounts.Account;
import com.carddemo.accounts.AccountRepository;
import com.carddemo.accounts.CardXref;
import com.carddemo.accounts.CardXrefRepository;
import com.carddemo.accounts.Customer;
import com.carddemo.accounts.CustomerRepository;
import com.carddemo.transactions.Transaction;
import com.carddemo.transactions.TransactionRepository;
import org.springframework.batch.core.JobParameters;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CREASTMT (the DFSORT into TRXFL, then CBSTM03A with its file-access subroutine CBSTM03B): one statement per
 * card in the cross reference, in card order, as 80-column text (STMTFILE) and as an HTML page (HTMLFILE), with
 * the customer's name and address, the account's balance, and the card's transactions in TRAN-ID order. The SQL
 * query replaces the sort; the legacy program's TIOT listing of its DD names has no counterpart.
 */
@Component
public class AccountStatements implements JclJob {

    private static final String STARS = "*".repeat(31);
    private static final String DASHES = "-".repeat(80);

    private final CardXrefRepository xrefs;
    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public AccountStatements(CardXrefRepository xrefs, CustomerRepository customers, AccountRepository accounts,
                             TransactionRepository transactions) {
        this.xrefs = xrefs;
        this.customers = customers;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Override
    public String jobName() {
        return "CREASTMT";
    }

    @Override
    public String programName() {
        return "CBSTM03A";
    }

    @Override
    public void run(JobParameters parameters, StepOutput out) {
        List<Transaction> all = transactions.byCard();
        if (all.isEmpty()) {
            throw abend(out, "TRNXFILE", "10");
        }
        Map<String, List<Transaction>> byCard = all.stream().collect(Collectors.groupingBy(Transaction::cardNumber));
        Statements statements = new Statements();
        for (CardXref xref : xrefs.all()) {
            Customer customer = customers.find(xref.customerId()).orElseThrow(() -> abend(out, "CUSTFILE", "23"));
            Account account = accounts.find(xref.accountId()).orElseThrow(() -> abend(out, "ACCTFILE", "23"));
            statements.write(customer, account, byCard.getOrDefault(xref.cardNumber(), List.of()));
        }
        out.dataset("STMTFILE", statements.text);
        out.dataset("HTMLFILE", statements.html);
    }

    private static IllegalStateException abend(StepOutput out, String dd, String status) {
        out.display("ERROR READING " + dd);
        out.display("RETURN CODE: " + status);
        out.display("ABENDING PROGRAM");
        return new IllegalStateException("ERROR READING " + dd + " " + status);
    }

    /** The two statement files; each statement is the STATEMENT-LINES and HTML-LINES the legacy writes. */
    private static final class Statements {

        final List<String> text = new ArrayList<>();
        final List<String> html = new ArrayList<>();

        void write(Customer customer, Account account, List<Transaction> cardTransactions) {
            String name = fit(firstWord(customer.firstName()) + " " + firstWord(customer.middleName()) + " "
                    + firstWord(customer.lastName()) + " ", 75);
            String address1 = fit(customer.addressLine1(), 50);
            String address2 = fit(customer.addressLine2(), 50);
            String address3 = fit(firstWord(customer.addressLine3()) + " " + firstWord(customer.stateCode()) + " "
                    + firstWord(customer.countryCode()) + " " + firstWord(customer.zip()) + " ", 80);
            String accountId = fit("%011d".formatted(account.id()), 20);
            String balance = Edited.zeroFilledTrailingMinus(account.currentBalance());
            String fico = fit("%03d".formatted(customer.ficoScore()), 20);

            text.add(STARS + "START OF STATEMENT" + STARS);
            text.add(name);
            text.add(address1);
            text.add(address2);
            text.add(address3);
            text.add(DASHES);
            text.add(" ".repeat(33) + "Basic Details");
            text.add(DASHES);
            text.add("Account ID         :" + accountId);
            text.add("Current Balance    :" + balance);
            text.add("FICO Score         :" + fico);
            text.add(DASHES);
            text.add(" ".repeat(30) + "TRANSACTION SUMMARY");
            text.add(DASHES);
            text.add("Tran ID         Tran Details    " + " ".repeat(35) + "  Tran Amount");
            text.add(DASHES);

            html.addAll(List.of("<!DOCTYPE html>", "<html lang=\"en\">", "<head>", "<meta charset=\"utf-8\">",
                    "<title>HTML Table Layout</title>", "</head>", "<body style=\"margin:0px;\">",
                    "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">",
                    "<tr>", Html.TITLE_CELL, "<h3>Statement for Account Number: " + accountId + "</h3>", "</td>",
                    "</tr>", "<tr>", "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">",
                    "<p style=\"font-size:16px\">Bank of XYZ</p>", "<p>410 Terry Ave N</p>", "<p>Seattle WA 99999</p>",
                    "</td>", "</tr>", "<tr>", Html.GREY_CELL,
                    "<p style=\"font-size:16px\">" + upToDoubleSpace(name.substring(0, 50)) + "  </p>",
                    "<p>" + upToDoubleSpace(address1) + "  </p>",
                    "<p>" + upToDoubleSpace(address2) + "  </p>",
                    "<p>" + upToDoubleSpace(address3) + "  </p>",
                    "</td>", "</tr>", "<tr>", Html.HEADING_CELL, "<p style=\"font-size:16px\">Basic Details</p>",
                    "</td>", "</tr>", "<tr>", Html.GREY_CELL,
                    "<p>Account ID         : " + accountId + "</p>",
                    "<p>Current Balance    : " + balance + "</p>",
                    "<p>FICO Score         : " + fico + "</p>",
                    "</td>", "</tr>", "<tr>", Html.HEADING_CELL, "<p style=\"font-size:16px\">Transaction Summary</p>",
                    "</td>", "</tr>", "<tr>",
                    Html.column(25, "#33FF5E", "left"), "<p style=\"font-size:16px\">Tran ID</p>", "</td>",
                    Html.column(55, "#33FF5E", "left"), "<p style=\"font-size:16px\">Tran Details</p>", "</td>",
                    Html.column(20, "#33FF5E", "right"), "<p style=\"font-size:16px\">Amount</p>", "</td>",
                    "</tr>"));

            BigDecimal total = BigDecimal.ZERO;
            for (Transaction t : cardTransactions) {
                String id = fit(t.id(), 16);
                String description = fit(t.description(), 49);
                String amount = Edited.trailingMinus(t.amount());
                text.add(id + " " + description + "$" + amount);
                html.addAll(List.of("<tr>",
                        Html.column(25, "#f2f2f2", "left"), "<p>" + id + "</p>", "</td>",
                        Html.column(55, "#f2f2f2", "left"), "<p>" + description + "</p>", "</td>",
                        Html.column(20, "#f2f2f2", "right"), "<p>" + amount + "</p>", "</td>",
                        "</tr>"));
                total = total.add(t.amount());
            }

            text.add(DASHES);
            text.add("Total EXP:" + " ".repeat(56) + "$" + Edited.trailingMinus(total));
            text.add("*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));
            html.addAll(List.of("<tr>", Html.TITLE_CELL, "<h3>End of Statement</h3>", "</td>", "</tr>",
                    "</table>", "</body>", "</html>"));
        }
    }

    private static final class Html {

        static final String TITLE_CELL = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";
        static final String GREY_CELL = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
        static final String HEADING_CELL =
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";

        static String column(int widthPercent, String color, String align) {
            return "<td style=\"width:%d%%; padding:0px 5px; background-color:%s; text-align:%s;\">"
                    .formatted(widthPercent, color, align);
        }
    }

    /** STRING ... DELIMITED BY ' ': a field with a leading space contributes nothing. */
    private static String firstWord(String field) {
        if (field == null) {
            return "";
        }
        int space = field.indexOf(' ');
        return space < 0 ? field : field.substring(0, space);
    }

    /** STRING ... DELIMITED BY '  ', on a field that is space-filled to its full width. */
    private static String upToDoubleSpace(String field) {
        int gap = field.indexOf("  ");
        return gap < 0 ? field : field.substring(0, gap);
    }

    /** MOVE into a PIC X(width): truncated or space-filled. */
    private static String fit(String value, int width) {
        String v = value == null ? "" : value;
        return v.length() >= width ? v.substring(0, width) : v + " ".repeat(width - v.length());
    }
}
