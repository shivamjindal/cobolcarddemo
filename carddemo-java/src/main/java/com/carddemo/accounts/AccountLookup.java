package com.carddemo.accounts;

import com.carddemo.online.Message;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 9000-READ-ACCT of COACTVWC and COACTUPC: the account's first card xref (CXACAIX), the account (ACCTDAT),
 * then the xref's customer (CUSTDAT), with the legacy message for whichever read misses. A missing
 * account stops the lookup, where the legacy programs went on to read the customer.
 */
@Component
class AccountLookup {

    /** What the reads found. */
    sealed interface Result {
    }

    record Found(Account account, Customer customer) implements Result {
    }

    /** The account was read but its customer was not. */
    record AccountOnly(Account account, Message message) implements Result {
    }

    record Failed(Message message) implements Result {
    }

    // CICS RESP and RESP2 values, which the legacy messages show.
    private static final int NOTFND = 13;
    private static final int NOTFND_ON_READ = 80;
    private static final int IOERR = 17;

    private final CardXrefRepository xrefs;
    private final AccountRepository accounts;
    private final CustomerRepository customers;

    AccountLookup(CardXrefRepository xrefs, AccountRepository accounts, CustomerRepository customers) {
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.customers = customers;
    }

    Result find(long accountId) {
        String id = Pictures.accountId(accountId);
        Optional<CardXref> xref;
        try {
            xref = xrefs.findByAccount(accountId);
        } catch (DataAccessException e) {
            return new Failed(fileError("CXACAIX"));
        }
        if (xref.isEmpty()) {
            return new Failed(legacy("Account:" + id + " not found in Cross ref file.  Resp:" + resp(NOTFND)
                    + " Reas:" + resp(NOTFND_ON_READ)));
        }
        Optional<Account> account;
        try {
            account = accounts.find(accountId);
        } catch (DataAccessException e) {
            return new Failed(fileError("ACCTDAT"));
        }
        if (account.isEmpty()) {
            return new Failed(legacy("Account:" + id + " not found in Acct Master file.Resp:" + resp(NOTFND)
                    + " Reas:" + resp(NOTFND_ON_READ)));
        }
        int customerId = xref.get().customerId();
        Optional<Customer> customer;
        try {
            customer = customers.find(customerId);
        } catch (DataAccessException e) {
            return new AccountOnly(account.get(), fileError("CUSTDAT"));
        }
        if (customer.isEmpty()) {
            return new AccountOnly(account.get(), legacy("CustId:" + Pictures.customerId(customerId)
                    + " not found in customer master.Resp: " + resp(NOTFND) + " REAS:" + resp(NOTFND_ON_READ)));
        }
        return new Found(account.get(), customer.get());
    }

    private static Message fileError(String file) {
        return legacy("File Error: " + pad("READ", 8) + " on " + pad(file, 9) + " returned RESP " + resp(IOERR)
                + ",RESP2 " + resp(0));
    }

    /** ERROR-RESP is X(10) receiving S9(9) COMP. */
    private static String resp(int code) {
        return String.format("%09d ", code);
    }

    private static String pad(String text, int width) {
        return String.format("%-" + width + "s", text);
    }

    /** WS-RETURN-MSG is X(75), so the longer messages lose their tail. */
    private static Message legacy(String text) {
        return Message.error((text.length() > 75 ? text.substring(0, 75) : text).stripTrailing());
    }
}
