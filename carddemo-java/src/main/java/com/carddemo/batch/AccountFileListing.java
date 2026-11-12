package com.carddemo.batch;

import com.carddemo.vsam.Column;
import com.carddemo.vsam.Table;
import com.carddemo.vsam.VsamDataset;
import org.springframework.batch.core.JobParameters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * READACCT (CBACT01C): lists every account to SYSOUT field by field and copies it into three sample layouts,
 * OUTFILE (the reissue date as YYYYMMDD, the cycle debit packed), ARRYFILE (a five-entry balance array, three
 * entries filled with constants) and VBRCFILE (a short and a long variable-length record per account).
 */
@Component
public class AccountFileListing implements JclJob {

    private static final Table ACCTDATA = Table.of(VsamDataset.ACCOUNTS);
    private static final Column CURR_BAL = column("acct_curr_bal");

    private final JdbcTemplate jdbc;

    public AccountFileListing(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String jobName() {
        return "READACCT";
    }

    @Override
    public String programName() {
        return "CBACT01C";
    }

    @Override
    public void run(JobParameters parameters, StepOutput out) {
        List<String> outfile = new ArrayList<>();
        List<String> arryfile = new ArrayList<>();
        List<String> vbrcfile = new ArrayList<>();
        // OUT-ACCT-CURR-CYC-DEBIT is only set when the account's cycle debit is zero, so any other account
        // inherits the previous record's value.
        String cycleDebit = packed(BigDecimal.ZERO);
        out.display("START OF EXECUTION OF PROGRAM CBACT01C");
        for (String record : ACCTDATA.unload(jdbc)) {
            for (String name : List.of("ACCT-ID", "ACCT-ACTIVE-STATUS", "ACCT-CURR-BAL", "ACCT-CREDIT-LIMIT",
                    "ACCT-CASH-CREDIT-LIMIT", "ACCT-OPEN-DATE", "ACCT-EXPIRAION-DATE", "ACCT-REISSUE-DATE",
                    "ACCT-CURR-CYC-CREDIT", "ACCT-CURR-CYC-DEBIT", "ACCT-GROUP-ID")) {
                out.display("%-24s:%s".formatted(name, display(record, column(name))));
            }
            out.display("-".repeat(49));

            if (((BigDecimal) column("acct_curr_cyc_debit").decode(record)).signum() == 0) {
                cycleDebit = packed(new BigDecimal("2525.00"));
            }
            LocalDate reissue = (LocalDate) column("acct_reissue_date").decode(record);
            String reissueOut = reissue == null ? "" : reissue.format(DateTimeFormatter.BASIC_ISO_DATE);
            outfile.add(raw(record, "acct_id") + raw(record, "acct_active_status") + raw(record, "acct_curr_bal")
                    + raw(record, "acct_credit_limit") + raw(record, "acct_cash_credit_limit")
                    + raw(record, "acct_open_date") + raw(record, "acct_expiraion_date")
                    + "%-10s".formatted(reissueOut) + raw(record, "acct_curr_cyc_credit") + cycleDebit
                    + raw(record, "acct_group_id"));

            String balance = raw(record, "acct_curr_bal");
            String initialized = "0".repeat(12) + packed(BigDecimal.ZERO);
            arryfile.add(raw(record, "acct_id")
                    + balance + packed(new BigDecimal("1005.00"))
                    + balance + packed(new BigDecimal("1525.00"))
                    + CURR_BAL.encode(new BigDecimal("-1025.00")) + packed(new BigDecimal("-2500.00"))
                    + initialized + initialized + " ".repeat(4));

            String vbrc1 = raw(record, "acct_id") + raw(record, "acct_active_status");
            String vbrc2 = raw(record, "acct_id") + balance + raw(record, "acct_credit_limit")
                    + raw(record, "acct_reissue_date").substring(0, 4);
            out.display("VBRC-REC1:" + vbrc1);
            out.display("VBRC-REC2:" + vbrc2);
            vbrcfile.add(vbrc1);
            vbrcfile.add(vbrc2);

            out.display(record);
        }
        out.display("END OF EXECUTION OF PROGRAM CBACT01C");
        out.dataset("OUTFILE", outfile);
        out.dataset("ARRYFILE", arryfile);
        out.dataset("VBRCFILE", vbrcfile);
    }

    private static Column column(String name) {
        String key = name.toLowerCase(Locale.ROOT).replace('-', '_');
        return ACCTDATA.columns().get(ACCTDATA.columnIndex(key));
    }

    private static String raw(String record, String name) {
        Column column = column(name);
        return record.substring(column.field().offset(), column.field().end());
    }

    /** DISPLAY of the field: a signed {@code S9(10)V99} as sign, digits and point; anything else as its bytes. */
    private static String display(String record, Column column) {
        if (column.decode(record) instanceof BigDecimal amount) {
            return (amount.signum() < 0 ? "-" : "+") + String.format(Locale.ROOT, "%013.2f", amount.abs());
        }
        return record.substring(column.field().offset(), column.field().end());
    }

    /** {@code S9(10)V99 COMP-3}: seven bytes, two digits each, the last nibble the sign (C plus, D minus). */
    private static String packed(BigDecimal value) {
        String nibbles = "%013d".formatted(value.movePointRight(2).abs().longValueExact())
                + (value.signum() < 0 ? 'd' : 'c');
        StringBuilder bytes = new StringBuilder();
        for (int i = 0; i < nibbles.length(); i += 2) {
            bytes.append((char) Integer.parseInt(nibbles.substring(i, i + 2), 16));
        }
        return bytes.toString();
    }
}
