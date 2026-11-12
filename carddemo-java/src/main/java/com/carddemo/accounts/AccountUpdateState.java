package com.carddemo.accounts;

import java.io.Serializable;

import static com.carddemo.accounts.AccountUpdatePhase.CONFIRM;
import static com.carddemo.accounts.AccountUpdatePhase.INVALID;
import static com.carddemo.accounts.AccountUpdatePhase.NOT_FETCHED;
import static com.carddemo.accounts.AccountUpdatePhase.SHOWN;

/**
 * COACTUPC's program COMMAREA: the phase, the account and customer as fetched (ACUP-OLD-*) while a record
 * is on screen, and in CONFIRM the validated values (ACUP-NEW-*) that F5 saves.
 */
public record AccountUpdateState(AccountUpdatePhase phase, Account account, Customer customer,
                                 Account newAccount, Customer newCustomer) implements Serializable {

    public AccountUpdateState {
        boolean fetched = phase == SHOWN || phase == INVALID || phase == CONFIRM;
        if ((account != null) != fetched || (customer != null) != fetched) {
            throw new IllegalArgumentException(phase + " must " + (fetched ? "" : "not ") + "hold the fetched record");
        }
        if ((newAccount != null) != (phase == CONFIRM) || (newCustomer != null) != (phase == CONFIRM)) {
            throw new IllegalArgumentException("Only CONFIRM holds validated values, not " + phase);
        }
    }

    public static AccountUpdateState notFetched() {
        return new AccountUpdateState(NOT_FETCHED, null, null, null, null);
    }

    public static AccountUpdateState shown(Account account, Customer customer) {
        return new AccountUpdateState(SHOWN, account, customer, null, null);
    }

    public static AccountUpdateState invalid(Account account, Customer customer) {
        return new AccountUpdateState(INVALID, account, customer, null, null);
    }

    public static AccountUpdateState confirm(Account account, Customer customer, Account newAccount,
                                             Customer newCustomer) {
        return new AccountUpdateState(CONFIRM, account, customer, newAccount, newCustomer);
    }

    /** SAVED, LOCK_FAILED or SAVE_FAILED: the attempt is over and nothing more is needed. */
    public static AccountUpdateState ended(AccountUpdatePhase phase) {
        if (!phase.ended()) {
            throw new IllegalArgumentException(phase + " is not the end of a save");
        }
        return new AccountUpdateState(phase, null, null, null, null);
    }
}
