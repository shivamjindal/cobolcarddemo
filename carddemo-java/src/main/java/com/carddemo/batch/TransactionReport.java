package com.carddemo.batch;

import com.carddemo.accounts.CardXref;
import com.carddemo.accounts.CardXrefRepository;
import com.carddemo.transactions.Transaction;
import com.carddemo.transactions.TransactionRepository;
import org.springframework.batch.core.JobParameters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * TRANREPT (REPROC, the DFSORT INCLUDE/SORT, then CBTRN03C): the 133-column daily transaction report of every
 * transaction processed in a date window, in card number order, with account, page (every 20 lines) and grand
 * totals. The SQL query replaces the backup, filter and sort steps.
 */
@Component
public class TransactionReport implements JclJob {

    public static final String START = "start";
    public static final String END = "end";

    private static final int PAGE_SIZE = 20;
    private static final String DASHES = "-".repeat(133);
    private static final String HEADER_1 = "%-17s%-12s%-19s%-35s%-14s %-16s".formatted(
            "Transaction ID", "Account ID", "Transaction Type", "Tran Category", "Tran Source", "        Amount");

    private final JdbcTemplate jdbc;
    private final CardXrefRepository xrefs;
    private final TransactionRepository transactions;

    public TransactionReport(JdbcTemplate jdbc, CardXrefRepository xrefs, TransactionRepository transactions) {
        this.jdbc = jdbc;
        this.xrefs = xrefs;
        this.transactions = transactions;
    }

    @Override
    public String jobName() {
        return "TRANREPT";
    }

    @Override
    public String programName() {
        return "CBTRN03C";
    }

    @Override
    public void run(JobParameters parameters, StepOutput out) {
        LocalDate start = LocalDate.parse(parameters.getString(START));
        LocalDate end = LocalDate.parse(parameters.getString(END));
        out.display("START OF EXECUTION OF PROGRAM CBTRN03C");
        out.display("Reporting from " + start + " to " + end);
        Page page = new Page(start, end);
        String card = null;
        CardXref xref = null;
        for (Transaction t : transactions.processedBetween(start, end)) {
            out.display(t.encode());
            if (!t.cardNumber().equals(card)) {
                if (card != null) {
                    page.accountTotals();
                }
                card = t.cardNumber();
                xref = xrefs.find(card).orElseThrow(() -> abend(out, "INVALID CARD NUMBER : " + t.cardNumber()));
            }
            page.detail(t, xref.accountId(), typeDescription(t, out), categoryDescription(t, out));
        }
        if (card != null) {
            page.accountTotals();
        }
        out.display("TRAN-AMT " + Edited.display(page.lastAmount));
        out.display("WS-PAGE-TOTAL" + Edited.display(page.pageTotal));
        page.pageTotals();
        page.grandTotals();
        out.dataset("TRANREPT", page.lines);
        out.display("END OF EXECUTION OF PROGRAM CBTRN03C");
    }

    private String typeDescription(Transaction t, StepOutput out) {
        return jdbc.query("SELECT tran_type_desc FROM tran_types WHERE tran_type = ?", (rs, n) -> rs.getString(1),
                t.typeCode()).stream().findFirst()
                .orElseThrow(() -> abend(out, "INVALID TRANSACTION TYPE : " + t.typeCode()));
    }

    private String categoryDescription(Transaction t, StepOutput out) {
        return jdbc.query("SELECT tran_cat_type_desc FROM tran_categories WHERE tran_type_cd = ? AND tran_cat_cd = ?",
                (rs, n) -> rs.getString(1), t.typeCode(), t.categoryCode()).stream().findFirst()
                .orElseThrow(() -> abend(out, "INVALID TRAN CATG KEY : %s%04d".formatted(t.typeCode(), t.categoryCode())));
    }

    private static IllegalStateException abend(StepOutput out, String message) {
        out.display(message);
        out.display("FILE STATUS IS: NNNN0023");
        out.display("ABENDING PROGRAM");
        return new IllegalStateException(message);
    }

    /** The report file and its running totals (WS-REPORT-VARS). */
    private static final class Page {

        final List<String> lines = new ArrayList<>();
        final String nameHeader;
        int lineCounter;
        boolean headed;
        BigDecimal pageTotal = BigDecimal.ZERO;
        BigDecimal accountTotal = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;
        BigDecimal lastAmount = BigDecimal.ZERO;

        Page(LocalDate start, LocalDate end) {
            nameHeader = "%-38s%-41s%-12s%s to %s".formatted("DALYREPT", "Daily Transaction Report", "Date Range: ", start, end);
        }

        void detail(Transaction t, long accountId, String typeDescription, String categoryDescription) {
            if (!headed) {
                headed = true;
                headers();
            }
            if (lineCounter % PAGE_SIZE == 0) {
                pageTotals();
                headers();
            }
            pageTotal = pageTotal.add(t.amount());
            accountTotal = accountTotal.add(t.amount());
            lastAmount = t.amount();
            write("%-16s %011d %-2s-%-15.15s %04d-%-29.29s %-10.10s    %s  ".formatted(t.id(), accountId, t.typeCode(),
                    typeDescription, t.categoryCode(), categoryDescription, t.source(), Edited.minusAmount(t.amount())));
        }

        void pageTotals() {
            write("%-11s%s%s".formatted("Page Total", ".".repeat(86), Edited.signedAmount(pageTotal)));
            grandTotal = grandTotal.add(pageTotal);
            pageTotal = BigDecimal.ZERO;
            write(DASHES);
        }

        void accountTotals() {
            write("%-13s%s%s".formatted("Account Total", ".".repeat(84), Edited.signedAmount(accountTotal)));
            accountTotal = BigDecimal.ZERO;
            write(DASHES);
        }

        void grandTotals() {
            lines.add(pad("%-11s%s%s".formatted("Grand Total", ".".repeat(86), Edited.signedAmount(grandTotal))));
        }

        private void headers() {
            write(nameHeader);
            write("");
            write(HEADER_1);
            write(DASHES);
        }

        private void write(String line) {
            lines.add(pad(line));
            lineCounter++;
        }

        private static String pad(String line) {
            return "%-133s".formatted(line);
        }
    }
}
