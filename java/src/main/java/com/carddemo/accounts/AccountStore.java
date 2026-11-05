package com.carddemo.accounts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.carddemo.cics.CicsFile;
import com.carddemo.cics.CicsRegion;

/** ACCTDAT, table {@code accounts}. */
@Component
public final class AccountStore {

    private static final String COLUMNS = "acct_id, acct_active_status, acct_curr_bal, acct_credit_limit, "
            + "acct_cash_credit_limit, acct_open_date, acct_expiraion_date, acct_reissue_date, acct_curr_cyc_credit, "
            + "acct_curr_cyc_debit, acct_addr_zip, acct_group_id, acct_filler";

    private final KeyedFile<AccountRecord> file;

    public AccountStore(JdbcClient jdbc, CicsRegion region) {
        this.file = new KeyedFile<>(jdbc, region, CicsFile.ACCTDAT, "accounts", COLUMNS, "acct_id", AccountStore::row);
    }

    public Read<AccountRecord> find(long id) {
        return file.read(id);
    }

    public ForUpdate<AccountRecord> findForUpdate(long id) {
        return file.readForUpdate(id);
    }

    public Write rewrite(Held<AccountRecord> held, AccountRecord edited) {
        if (edited.id() != held.record().id()) {
            throw new IllegalArgumentException("REWRITE changes the key " + held.record().id() + " to " + edited.id());
        }
        return file.rewrite(edited.id(), "acct_active_status = ?, acct_curr_bal = ?, acct_credit_limit = ?, "
                        + "acct_cash_credit_limit = ?, acct_open_date = ?, acct_expiraion_date = ?, acct_reissue_date = ?, "
                        + "acct_curr_cyc_credit = ?, acct_curr_cyc_debit = ?, acct_addr_zip = ?, acct_group_id = ?, "
                        + "acct_filler = ?",
                edited.activeStatus(), edited.currBal(), edited.creditLimit(), edited.cashCreditLimit(),
                edited.openDate(), edited.expirationDate(), edited.reissueDate(), edited.currCycCredit(),
                edited.currCycDebit(), edited.addrZip(), edited.groupId(), edited.filler());
    }

    private static AccountRecord row(ResultSet rs, int rowNum) throws SQLException {
        return new AccountRecord(rs.getLong("acct_id"), rs.getString("acct_active_status"),
                rs.getBigDecimal("acct_curr_bal"), rs.getBigDecimal("acct_credit_limit"),
                rs.getBigDecimal("acct_cash_credit_limit"), rs.getObject("acct_open_date", LocalDate.class),
                rs.getObject("acct_expiraion_date", LocalDate.class), rs.getObject("acct_reissue_date", LocalDate.class),
                rs.getBigDecimal("acct_curr_cyc_credit"), rs.getBigDecimal("acct_curr_cyc_debit"),
                rs.getString("acct_addr_zip"), rs.getString("acct_group_id"), rs.getString("acct_filler"));
    }
}
