package com.carddemo.batch;

import com.carddemo.accounts.Account;
import com.carddemo.accounts.AccountRepository;
import com.carddemo.accounts.CardXref;
import com.carddemo.accounts.CardXrefRepository;
import com.carddemo.transactions.TranCatBalance;
import com.carddemo.transactions.TranCatBalanceRepository;
import com.carddemo.transactions.Transaction;
import com.carddemo.transactions.TransactionRepository;
import com.carddemo.vsam.Table;
import com.carddemo.vsam.VsamDataset;
import org.springframework.batch.core.JobParameters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * INTCALC (CBACT04C) and COMBTRAN: charges a month's interest on every category balance at its disclosure
 * group's rate, posts one interest transaction per category, adds the total to the account and closes the
 * cycle. The legacy wrote the interest to SYSTRAN for COMBTRAN to sort into TRANSACT; here each account's
 * transactions and rewrite commit together, so SYSTRAN is only a listing of what was posted.
 *
 * <p>Interest TRAN-IDs are PARM-DATE plus a run-wide sequence. A rerun with the same PARM (a restart after a
 * failure) skips accounts that already have this PARM's interest and continues the sequence after it.
 */
@Component
public class InterestCalculation implements JclJob {

    public static final String PARM = "parm";

    private static final Table TCATBALF = Table.of(VsamDataset.TRAN_CAT_BALANCES);
    private static final String DEFAULT_GROUP = "DEFAULT";

    private final TranCatBalanceRepository balances;
    private final AccountRepository accounts;
    private final CardXrefRepository xrefs;
    private final TransactionRepository transactions;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InterestCalculation(TranCatBalanceRepository balances, AccountRepository accounts, CardXrefRepository xrefs,
                               TransactionRepository transactions, JdbcTemplate jdbc, TransactionTemplate tx,
                               Clock clock) {
        this.balances = balances;
        this.accounts = accounts;
        this.xrefs = xrefs;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public String jobName() {
        return "INTCALC";
    }

    @Override
    public String programName() {
        return "CBACT04C";
    }

    @Override
    public void run(JobParameters parameters, StepOutput out) {
        String parm = "%-10s".formatted(parameters.getString(PARM));
        List<Transaction> earlier = transactions.findByIdPrefix(parm);
        Set<String> charged = earlier.stream().map(Transaction::description).collect(Collectors.toSet());
        int[] suffix = {earlier.size()};
        List<String> systran = new ArrayList<>();
        out.display("START OF EXECUTION OF PROGRAM CBACT04C");
        Map<Long, List<TranCatBalance>> byAccount = balances.all().stream()
                .collect(Collectors.groupingBy(TranCatBalance::accountId, LinkedHashMap::new, Collectors.toList()));
        byAccount.forEach((accountId, categories) -> tx.executeWithoutResult(status -> {
            Optional<Account> locked = accounts.lock(accountId);
            Optional<CardXref> xref = xrefs.findByAccount(accountId);
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            boolean done = charged.contains(description(accountId));
            for (TranCatBalance category : categories) {
                out.display(TCATBALF.encode(new Object[] {category.accountId(), category.typeCode(),
                        category.categoryCode(), category.balance()}));
                if (locked.isEmpty()) {
                    out.display("ACCOUNT NOT FOUND: %011d".formatted(accountId));
                    continue;
                }
                if (xref.isEmpty()) {
                    out.display("ACCOUNT NOT FOUND: %011d".formatted(accountId));
                }
                BigDecimal rate = rate(locked.get().groupId(), category, out);
                if (rate.signum() == 0 || done) {
                    continue;
                }
                BigDecimal interest = category.balance().multiply(rate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.DOWN);
                total = total.add(interest);
                LocalDateTime now = CurrentDate.timestamp(clock);
                Transaction t = new Transaction(parm + "%06d".formatted(++suffix[0]), "01", 5, "System",
                        description(accountId), interest, 0, "", "", "", xref.map(CardXref::cardNumber).orElse(""),
                        now, now);
                transactions.post(t);
                systran.add(t.encode());
            }
            if (locked.isPresent()) {
                Account a = locked.get();
                accounts.rewrite(a.withBalances(a.currentBalance().add(total), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2)));
            }
        }));
        out.dataset("SYSTRAN", systran);
        out.display("END OF EXECUTION OF PROGRAM CBACT04C");
    }

    private static String description(long accountId) {
        return "Int. for a/c %011d".formatted(accountId);
    }

    /** DISCGRP for the account's group, falling back to the DEFAULT group's rate for the same type and category. */
    private BigDecimal rate(String group, TranCatBalance category, StepOutput out) {
        Optional<BigDecimal> rate = discloseRate(group, category);
        if (rate.isPresent()) {
            return rate.get();
        }
        out.display("DISCLOSURE GROUP RECORD MISSING");
        out.display("TRY WITH DEFAULT GROUP CODE");
        return discloseRate(DEFAULT_GROUP, category).orElseThrow(() -> new IllegalStateException(
                "ERROR READING DEFAULT DISCLOSURE GROUP for " + category.key()));
    }

    private Optional<BigDecimal> discloseRate(String group, TranCatBalance category) {
        return jdbc.query("SELECT dis_int_rate FROM disclosure_groups WHERE dis_acct_group_id = ?"
                        + " AND dis_tran_type_cd = ? AND dis_tran_cat_cd = ?", (rs, n) -> rs.getBigDecimal(1),
                group, category.typeCode(), category.categoryCode()).stream().findFirst();
    }
}
