package com.carddemo.transactions;

import com.carddemo.accounts.Account;
import com.carddemo.accounts.AccountRepository;
import com.carddemo.accounts.CardXref;
import com.carddemo.accounts.CardXrefRepository;
import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * COBIL00C. Pays the account's whole balance. Every ENTER re-reads the screen, so nothing is kept between
 * screens. A confirmed payment locks the account, then writes the payment and the new balance in one
 * transaction; any refusal or failed write rolls both back.
 */
@Component
class BillPayment implements ScreenProgram {

    private static final String ID = "ACTIDIN";
    private static final String BALANCE = "CURBAL";
    private static final String CONFIRM = "CONFIRM";

    private static final String NOT_FOUND = "Account ID NOT found...";
    private static final String LOOKUP_FAILED = "Unable to lookup Account...";

    /** CONFIRMI as PROCESS-ENTER-KEY evaluates it. */
    private enum Confirmation {
        PAY, CANCEL, ASK, INVALID;

        static Confirmation of(String typed) {
            return switch (typed) {
                case "Y", "y" -> PAY;
                case "N", "n" -> CANCEL;
                case "" -> ASK;
                default -> INVALID;
            };
        }
    }

    /** Why nothing was paid, and the balance to show once the account was read. */
    private static final class Refused extends RuntimeException {

        private final transient Optional<BigDecimal> balance;

        Refused(String message) {
            this(message, Optional.empty());
        }

        Refused(String message, Account account) {
            this(message, Optional.of(account.currentBalance()));
        }

        private Refused(String message, Optional<BigDecimal> balance) {
            super(message, null, false, false);
            this.balance = balance;
        }
    }

    private final AccountRepository accounts;
    private final CardXrefRepository xrefs;
    private final TransactionRepository transactions;
    private final TransactionTemplate tx;
    private final Clock clock;

    BillPayment(AccountRepository accounts, CardXrefRepository xrefs, TransactionRepository transactions,
                TransactionTemplate tx, Clock clock) {
        this.accounts = accounts;
        this.xrefs = xrefs;
        this.transactions = transactions;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Program program() {
        return Program.COBIL00C;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return cleared();
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        return switch (aid) {
            case ENTER -> enter(fields);
            case PF3 -> new Outcome.Transfer(conversation.from().orElse(Program.COMEN01C));
            case PF4 -> cleared();
            default -> screen(fields, Optional.empty(), Message.INVALID_KEY, ID);
        };
    }

    private Outcome enter(Map<String, String> fields) {
        String typed = fields.getOrDefault(ID, "");
        if (typed.isEmpty()) {
            return screen(fields, Optional.empty(), Message.error("Acct ID can NOT be empty..."), ID);
        }
        try {
            return switch (Confirmation.of(fields.getOrDefault(CONFIRM, ""))) {
                case PAY -> paid(tx.execute(status -> pay(typed)));
                case CANCEL -> cleared();
                case ASK -> screen(fields, Optional.of(shown(typed).currentBalance()),
                        Message.info("Confirm to make a bill payment..."), CONFIRM);
                case INVALID -> screen(fields, Optional.empty(),
                        Message.error("Invalid value. Valid values are (Y/N)..."), CONFIRM);
            };
        } catch (Refused refused) {
            return screen(fields, refused.balance, Message.error(refused.getMessage()), ID);
        }
    }

    /** Legacy reads with UPDATE even to show the balance; this read holds no lock. */
    private Account shown(String typed) {
        return payable(command(new Refused(LOOKUP_FAILED), () -> accountId(typed).flatMap(accounts::find)));
    }

    /** Unlike the legacy program, a missing or unreadable xref stops the payment before anything is written. */
    private Transaction pay(String typed) {
        Account account = payable(command(new Refused(LOOKUP_FAILED), () -> accountId(typed).flatMap(accounts::lock)));
        CardXref xref = command(new Refused("Unable to lookup XREF AIX file...", account),
                () -> xrefs.findByAccount(account.id())).orElseThrow(() -> new Refused(NOT_FOUND, account));
        Transaction payment = command(new Refused("Unable to Add Bill pay Transaction...", account),
                () -> transactions.add(id -> payment(id, account, xref)));
        try {
            accounts.rewrite(account.withCurrentBalance(account.currentBalance().subtract(payment.amount())));
        } catch (DataAccessException e) {
            throw new Refused("Unable to Update Account...", account);
        }
        return payment;
    }

    private static Account payable(Optional<Account> account) {
        Account found = account.orElseThrow(() -> new Refused(NOT_FOUND));
        if (found.currentBalance().signum() <= 0) {
            throw new Refused("You have nothing to pay...", found);
        }
        return found;
    }

    private Transaction payment(String id, Account account, CardXref xref) {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        return new Transaction(id, "02", 2, "POS TERM", "BILL PAYMENT - ONLINE", account.currentBalance(), 999999999L,
                "BILL PAYMENT", "N/A", "N/A", xref.cardNumber(), now, now);
    }

    /** A file command whose RESP is not NORMAL refuses the payment with that command's message. */
    private static <T> T command(Refused failure, Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException e) {
            throw failure;
        }
    }

    /** ACTIDINI is moved unedited into the 9(11) key, so only eleven digits can match a record. */
    private static Optional<Long> accountId(String typed) {
        return typed.matches("\\d{11}") ? Optional.of(Long.parseLong(typed)) : Optional.empty();
    }

    private static Outcome paid(Transaction payment) {
        return new Outcome.Show(Map.of(),
                Message.success("Payment successful.  Your Transaction ID is " + payment.id() + "."), ID);
    }

    private static Outcome cleared() {
        return new Outcome.Show(Map.of(), null, ID);
    }

    /** The map is sent back as it was received, except the balance when this task read the account. */
    private static Outcome screen(Map<String, String> input, Optional<BigDecimal> balance, Message message,
                                  String cursor) {
        Map<String, String> shown = new LinkedHashMap<>();
        List.of(ID, BALANCE, CONFIRM).forEach(name -> shown.put(name, input.getOrDefault(name, "")));
        balance.ifPresent(b -> shown.put(BALANCE, String.format(Locale.ROOT, "%+014.2f", b)));
        return new Outcome.Show(shown, message, cursor);
    }
}
