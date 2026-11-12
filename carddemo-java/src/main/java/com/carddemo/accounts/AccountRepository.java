package com.carddemo.accounts;

import com.carddemo.vsam.RecordLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * ACCTDAT over the accounts table, and the account locking rule every writer follows (online update,
 * bill payment, POSTTRAN, INTCALC). See {@link RecordLock}: {@link #lock} is CICS READ UPDATE and
 * {@link #rewrite} is REWRITE, both inside the caller's transaction. A writer that also changes the
 * customer or a card locks the account first.
 */
@Repository
public class AccountRepository {

    private static final String COLUMNS = "acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,"
            + " acct_cash_credit_limit, acct_open_date, acct_expiraion_date, acct_reissue_date,"
            + " acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip, acct_group_id";

    private static final RowMapper<Account> ROW = (rs, n) -> new Account(rs.getLong(1), rs.getString(2),
            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getObject(6, LocalDate.class),
            rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class), rs.getBigDecimal(9),
            rs.getBigDecimal(10), rs.getString(11), rs.getString(12));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts WHERE acct_id = ?", ROW, id).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Account> lock(long id) {
        return RecordLock.read(jdbc, "SELECT " + COLUMNS + " FROM accounts WHERE acct_id = ? FOR UPDATE", ROW, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rewrite(Account a) {
        RecordLock.expectOne(jdbc.update("UPDATE accounts SET acct_active_status = ?, acct_curr_bal = ?,"
                        + " acct_credit_limit = ?, acct_cash_credit_limit = ?, acct_open_date = ?,"
                        + " acct_expiraion_date = ?, acct_reissue_date = ?, acct_curr_cyc_credit = ?,"
                        + " acct_curr_cyc_debit = ?, acct_addr_zip = ?, acct_group_id = ? WHERE acct_id = ?",
                a.activeStatus(), a.currentBalance(), a.creditLimit(), a.cashCreditLimit(), a.openDate(),
                a.expirationDate(), a.reissueDate(), a.currentCycleCredit(), a.currentCycleDebit(),
                a.addressZip(), a.groupId(), a.id()), "account " + a.id());
    }
}
