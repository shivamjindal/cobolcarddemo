package com.carddemo.batch;

import com.carddemo.accounts.Account;
import com.carddemo.accounts.AccountRepository;
import com.carddemo.accounts.CardXref;
import com.carddemo.accounts.CardXrefRepository;
import com.carddemo.transactions.TranCatBalance;
import com.carddemo.transactions.TranCatBalanceRepository;
import com.carddemo.transactions.Transaction;
import com.carddemo.transactions.TransactionRepository;
import com.carddemo.vsam.SeedFormat;
import org.springframework.batch.core.JobParameters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * POSTTRAN (CBTRN02C): validates each daily feed record against CCXREF and ACCTDAT, posts the good ones to
 * TRANSACT, TCATBALF and the account, and writes the rest to DALYREJS with a reason. Each record posts in its
 * own transaction under the account lock, so it interleaves safely with online bill payments. The outcome of
 * every record is kept (TRANSACT, or daily_rejects), so a rerun of the same feed after a failure repeats each
 * earlier outcome instead of failing on duplicate keys or re-judging a rejected record against newer balances.
 */
@Component
public class DailyPosting implements JclJob {

    public static final String FEED = "feed";
    public static final String FORMAT = "format";

    enum Reject {
        INVALID_CARD(100, "INVALID CARD NUMBER FOUND"),
        ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),
        OVERLIMIT(102, "OVERLIMIT TRANSACTION"),
        EXPIRED(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

        final int code;
        final String description;

        Reject(int code, String description) {
            this.code = code;
            this.description = description;
        }

        String trailer() {
            return DailyPosting.trailer(code, description);
        }
    }

    private final CardXrefRepository xrefs;
    private final AccountRepository accounts;
    private final TranCatBalanceRepository balances;
    private final TransactionRepository transactions;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DailyPosting(CardXrefRepository xrefs, AccountRepository accounts, TranCatBalanceRepository balances,
                        TransactionRepository transactions, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.balances = balances;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public String jobName() {
        return "POSTTRAN";
    }

    @Override
    public String programName() {
        return "CBTRN02C";
    }

    @Override
    public void run(JobParameters parameters, StepOutput out) {
        out.display("START OF EXECUTION OF PROGRAM CBTRN02C");
        SeedFormat format = SeedFormat.valueOf(parameters.getString(FORMAT, SeedFormat.ASCII.name()));
        List<String> feed = format.transactionRecords(Path.of(parameters.getString(FEED)));
        List<String> rejects = new ArrayList<>();
        for (String record : feed) {
            Optional<String> trailer = tx.execute(status -> post(record, out));
            trailer.ifPresent(t -> rejects.add(record + t));
        }
        out.dataset("DALYREJS", rejects);
        out.display("TRANSACTIONS PROCESSED :%09d".formatted(feed.size()));
        out.display("TRANSACTIONS REJECTED  :%09d".formatted(rejects.size()));
        if (!rejects.isEmpty()) {
            out.returnCode(4);
        }
        out.display("END OF EXECUTION OF PROGRAM CBTRN02C");
    }

    /** WS-VALIDATION-TRAILER, appended to the feed record in DALYREJS. */
    static String trailer(int code, String description) {
        return "%04d%-76s".formatted(code, description);
    }

    /** The DALYREJS trailer when the record is rejected. */
    private Optional<String> post(String record, StepOutput out) {
        Transaction daily = Transaction.decode(record);
        Optional<Transaction> existing = transactions.find(daily.id());
        if (existing.isPresent()) {
            if (!existing.get().processedAt(null).equals(daily.processedAt(null))) {
                throw new IllegalStateException("TRAN-ID " + daily.id() + " is already on file with other data");
            }
            out.display("ALREADY POSTED : " + daily.id());
            return Optional.empty();
        }
        Optional<String> rejected = jdbc.query("SELECT reason_code, reason_desc FROM daily_rejects WHERE tran_id = ? AND record = ?",
                (rs, n) -> trailer(rs.getInt(1), rs.getString(2)), daily.id(), record).stream().findFirst();
        if (rejected.isPresent()) {
            out.display("ALREADY REJECTED : " + daily.id());
            return rejected;
        }
        Optional<CardXref> xref = xrefs.find(daily.cardNumber());
        Optional<Account> locked = xref.flatMap(x -> accounts.lock(x.accountId()));
        Optional<Reject> reject = xref.isEmpty() ? Optional.of(Reject.INVALID_CARD)
                : locked.isEmpty() ? Optional.of(Reject.ACCOUNT_NOT_FOUND)
                : validate(locked.get(), daily);
        if (reject.isPresent()) {
            jdbc.update("""
                    INSERT INTO daily_rejects VALUES (?, ?, ?, ?)
                    ON CONFLICT (tran_id) DO UPDATE SET record = excluded.record, reason_code = excluded.reason_code,
                        reason_desc = excluded.reason_desc""",
                    daily.id(), record, reject.get().code, reject.get().description);
            return reject.map(Reject::trailer);
        }
        jdbc.update("DELETE FROM daily_rejects WHERE tran_id = ?", daily.id());
        Account account = locked.get();
        BigDecimal amount = daily.amount();
        balances.lock(account.id(), daily.typeCode(), daily.categoryCode()).ifPresentOrElse(
                b -> balances.rewrite(b.plus(amount)),
                () -> {
                    TranCatBalance created = new TranCatBalance(account.id(), daily.typeCode(), daily.categoryCode(), amount);
                    out.display("TCATBAL record not found for key : " + created.key() + ".. Creating.");
                    balances.write(created);
                });
        boolean toCycleCredit = amount.signum() >= 0;
        accounts.rewrite(account.withBalances(account.currentBalance().add(amount),
                toCycleCredit ? account.currentCycleCredit().add(amount) : account.currentCycleCredit(),
                toCycleCredit ? account.currentCycleDebit() : account.currentCycleDebit().add(amount)));
        transactions.post(daily.processedAt(CurrentDate.timestamp(clock)));
        return Optional.empty();
    }

    /**
     * Over the limit, then expired; the later failure wins, as in 1500-B-LOOKUP-ACCT. A blank expiration date
     * compares low, so it counts as expired. Cycle debit holds credits
     * as negative amounts, so the cycle's use of the limit is credit plus debit (legacy subtracted the debit,
     * which made every credit use up limit instead of freeing it).
     */
    private static Optional<Reject> validate(Account account, Transaction daily) {
        Reject reject = null;
        BigDecimal used = account.currentCycleCredit().add(account.currentCycleDebit()).add(daily.amount());
        if (account.creditLimit().compareTo(used) < 0) {
            reject = Reject.OVERLIMIT;
        }
        LocalDate expires = account.expirationDate();
        if (expires == null || daily.originated() != null && expires.isBefore(daily.originated().toLocalDate())) {
            reject = Reject.EXPIRED;
        }
        return Optional.ofNullable(reject);
    }
}
