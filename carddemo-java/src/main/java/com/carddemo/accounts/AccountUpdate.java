package com.carddemo.accounts;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.carddemo.accounts.AccountUpdatePhase.LOCK_FAILED;
import static com.carddemo.accounts.AccountUpdatePhase.SAVED;
import static com.carddemo.accounts.AccountUpdatePhase.SAVE_FAILED;

/**
 * COACTUPC. ENTER fetches an account with its customer, then validates the edited screen; F5 in CONFIRM
 * saves the values validated then. The save locks the account, then the customer, and writes only if both
 * still equal what was fetched. ACCT-ADDR-ZIP, which no field shows, is neither compared nor changed.
 */
@Component
class AccountUpdate implements ScreenProgram {

    private static final String ID = AccountUpdateForm.ACCOUNT_ID;

    /** How a save attempt ended, short of a failed write. */
    private sealed interface Attempt {
    }

    private record Saved() implements Attempt {
    }

    private record NotLocked(String message) implements Attempt {
    }

    private record Changed(Account account, Customer customer) implements Attempt {
    }

    private final AccountLookup lookup;
    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final TransactionTemplate tx;
    private final Clock clock;

    AccountUpdate(AccountLookup lookup, AccountRepository accounts, CustomerRepository customers,
                  TransactionTemplate tx, Clock clock) {
        this.lookup = lookup;
        this.accounts = accounts;
        this.customers = customers;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Program program() {
        return Program.COACTUPC;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return show(conversation, AccountUpdateState.notFetched(), new LinkedHashMap<>(), null, ID);
    }

    /** PF5 counts only in CONFIRM and PF12 only once a record is fetched; every other key acts as ENTER. */
    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        if (aid == Aid.PF3) {
            return new Outcome.Transfer(conversation.from().orElse(Program.COMEN01C));
        }
        AccountUpdateState state = conversation.get(AccountUpdateState.class).orElseGet(AccountUpdateState::notFetched);
        Map<String, String> input = AccountUpdateForm.input(fields);
        return switch (state.phase()) {
            case NOT_FETCHED -> search(conversation, input.getOrDefault(ID, ""));
            case SHOWN, INVALID -> aid == Aid.PF12 ? fetch(conversation, state.account().id())
                    : edit(conversation, state, input);
            case CONFIRM -> switch (aid) {
                case PF5 -> save(conversation, state);
                case PF12 -> fetch(conversation, state.account().id());
                default -> show(conversation, state, AccountUpdateForm.shown(state.newAccount(), state.newCustomer()),
                        null, ID);
            };
            case SAVED, LOCK_FAILED, SAVE_FAILED ->
                    show(conversation, AccountUpdateState.notFetched(), new LinkedHashMap<>(), null, ID);
        };
    }

    private Outcome search(Conversation conversation, String typed) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (typed.isEmpty()) {
            fields.put(ID, "*");
            return show(conversation, AccountUpdateState.notFetched(), fields, Message.error("No input received"), ID);
        }
        fields.put(ID, typed);
        if (!typed.matches("\\d{11}") || Long.parseLong(typed) == 0) {
            return show(conversation, AccountUpdateState.notFetched(), fields,
                    Message.error("Account Number if supplied must be a 11 digit Non-Zero Number"), ID);
        }
        return fetch(conversation, Long.parseLong(typed));
    }

    /** A customer that cannot be read leaves nothing to edit, so it fails the fetch like a missing account. */
    private Outcome fetch(Conversation conversation, long accountId) {
        return switch (lookup.find(accountId)) {
            case AccountLookup.Found found -> show(conversation,
                    AccountUpdateState.shown(found.account(), found.customer()),
                    AccountUpdateForm.shown(found.account(), found.customer()), null, ID);
            case AccountLookup.AccountOnly only -> notFetched(conversation, accountId, only.message());
            case AccountLookup.Failed failed -> notFetched(conversation, accountId, failed.message());
        };
    }

    private static Outcome notFetched(Conversation conversation, long accountId, Message message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ID, Pictures.accountId(accountId));
        return show(conversation, AccountUpdateState.notFetched(), fields, message, ID);
    }

    private Outcome edit(Conversation conversation, AccountUpdateState state, Map<String, String> input) {
        Account account = state.account();
        Customer customer = state.customer();
        if (!AccountUpdateForm.changed(input, account, customer)) {
            return show(conversation, state, AccountUpdateForm.shown(account, customer),
                    Message.info("No change detected with respect to values fetched."), "ACSTTUS");
        }
        AccountEdits.Problems problems = AccountEdits.check(input, LocalDate.now(clock));
        if (problems.message().isPresent()) {
            return show(conversation, AccountUpdateState.invalid(account, customer),
                    AccountUpdateForm.echo(input, problems.flags(), account, customer),
                    Message.error(problems.message().get()), AccountUpdateForm.cursor(problems.flags()));
        }
        Account newAccount = AccountUpdateForm.account(input, account);
        Customer newCustomer = AccountUpdateForm.customer(input, customer);
        return show(conversation, AccountUpdateState.confirm(account, customer, newAccount, newCustomer),
                AccountUpdateForm.shown(newAccount, newCustomer), null, ID);
    }

    private Outcome save(Conversation conversation, AccountUpdateState state) {
        Map<String, String> validated = AccountUpdateForm.shown(state.newAccount(), state.newCustomer());
        Attempt attempt;
        try {
            attempt = tx.execute(status -> attempt(status, state));
        } catch (DataAccessException e) {
            return show(conversation, AccountUpdateState.ended(SAVE_FAILED), validated,
                    Message.error("Update of record failed"), ID);
        }
        return switch (attempt) {
            case Saved saved -> show(conversation, AccountUpdateState.ended(SAVED), validated, null, ID);
            case NotLocked notLocked -> show(conversation, AccountUpdateState.ended(LOCK_FAILED), validated,
                    Message.error(notLocked.message()), ID);
            case Changed changed -> show(conversation, AccountUpdateState.shown(changed.account(), changed.customer()),
                    AccountUpdateForm.shown(changed.account(), changed.customer()),
                    Message.error("Record changed by some one else. Please review"), ID);
        };
    }

    private Attempt attempt(TransactionStatus status, AccountUpdateState state) {
        Optional<Account> account = locked(() -> accounts.lock(state.account().id()));
        if (account.isEmpty()) {
            status.setRollbackOnly();
            return new NotLocked("Could not lock account record for update");
        }
        Optional<Customer> customer = locked(() -> customers.lock(state.customer().id()));
        if (customer.isEmpty()) {
            status.setRollbackOnly();
            return new NotLocked("Could not lock customer record for update");
        }
        String zip = account.get().addressZip();
        if (!account.get().equals(state.account().withAddressZip(zip)) || !customer.get().equals(state.customer())) {
            status.setRollbackOnly();
            return new Changed(account.get(), customer.get());
        }
        accounts.rewrite(state.newAccount().withAddressZip(zip));
        customers.rewrite(state.newCustomer());
        return new Saved();
    }

    /** READ UPDATE: an absent row, a lock wait that timed out and a read error all mean no lock. */
    private static <T> Optional<T> locked(Supplier<Optional<T>> lock) {
        try {
            return lock.get();
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    private static Outcome show(Conversation conversation, AccountUpdateState state, Map<String, String> fields,
                                Message message, String cursor) {
        conversation.put(state);
        fields.put("INFOMSG", switch (state.phase()) {
            case NOT_FETCHED -> "Enter or update id of account to update";
            case SHOWN, INVALID -> "Update account details presented above.";
            case CONFIRM -> "Changes validated.Press F5 to save";
            case SAVED -> "Changes committed to database";
            case LOCK_FAILED, SAVE_FAILED -> "Changes unsuccessful. Please try again";
        });
        return new Outcome.Show(fields, message, cursor, AccountUpdateForm.readOnly(state.phase()));
    }
}
