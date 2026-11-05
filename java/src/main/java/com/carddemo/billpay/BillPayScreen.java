package com.carddemo.billpay;

import static com.carddemo.online.maps.COBIL0A.ACTIDIN;
import static com.carddemo.online.maps.COBIL0A.CONFIRM;
import static com.carddemo.online.maps.COBIL0A.CURBAL;
import static com.carddemo.online.maps.COBIL0A.ERRMSG;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.accounts.AccountRecord;
import com.carddemo.accounts.AccountStore;
import com.carddemo.accounts.CardXrefStore;
import com.carddemo.accounts.Held;
import com.carddemo.accounts.Read;
import com.carddemo.accounts.Write;
import com.carddemo.accounts.XrefRecord;
import com.carddemo.ksds.KeyedBrowse;
import com.carddemo.menu.MainMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.Outcome;
import com.carddemo.online.ProgramName;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COBIL0A;
import com.carddemo.transactions.TransactionRecord;
import com.carddemo.transactions.TransactionStore;

/** COBIL00C. Show the balance, and on Y write a payment for it and subtract that payment. */
@Component
public final class BillPayScreen extends Screen<COBIL0A, BillPayScreen.Action, BillPayScreen.State, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COBIL00C", "CB00", "bill-pay", NoHandoff.class);

    private static final Pattern ELEVEN_DIGITS = Pattern.compile("\\d{11}");
    private static final BigDecimal TRAN_AMT_MODULUS = BigDecimal.TEN.pow(9);
    private static final BigDecimal BALANCE_MODULUS = BigDecimal.TEN.pow(10);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String NOT_FOUND = "Account ID NOT found...";

    enum Action implements ScreenAction {
        PAY(Key.ENTER), BACK(Key.PF3), CLEAR(Key.PF4);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /** @param returnTo CDEMO-FROM-PROGRAM; PF3 goes there, or to the main menu when nothing sent us */
    record State(ProgramName returnTo) implements ScreenState {}

    private enum Confirm { YES, NO, BLANK, OTHER }

    private final AccountStore accounts;
    private final CardXrefStore xrefs;
    private final TransactionStore transactions;
    private final Clock clock;

    BillPayScreen(AccountStore accounts, CardXrefStore xrefs, TransactionStore transactions, Clock clock) {
        super(REF, COBIL0A.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.accounts = accounts;
        this.xrefs = xrefs;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return send(SymbolicMap.lowValues(COBIL0A.MAP).cursor(ACTIDIN), "",
                new State(arrival.from().orElse(MainMenuScreen.REF.program())));
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COBIL0A> map, State state) {
        return switch (action) {
            case PAY -> processEnterKey(map, state);
            case BACK -> transfer(state.returnTo());
            case CLEAR -> send(cleared(map), "", state);
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COBIL0A> map, State state) {
        return send(map.cursor(ACTIDIN), CommonMessages.INVALID_KEY, state);
    }

    private Outcome processEnterKey(SymbolicMap<COBIL0A> map, State state) {
        if (map.isBlank(ACTIDIN)) {
            return send(map.cursor(ACTIDIN), "Acct ID can NOT be empty...", state);
        }
        return switch (confirm(map.text(CONFIRM))) {
            case YES -> readAccount(map, state, true);
            case NO -> send(cleared(map), "", state);
            case BLANK -> readAccount(map, state, false);
            case OTHER -> send(map.cursor(CONFIRM), "Invalid value. Valid values are (Y/N)...", state);
        };
    }

    private Outcome readAccount(SymbolicMap<COBIL0A> map, State state, boolean paying) {
        Optional<Long> id = accountId(map.text(ACTIDIN));
        if (id.isEmpty()) {
            return send(map.cursor(ACTIDIN), NOT_FOUND, state);
        }
        return switch (accounts.findForUpdate(id.get())) {
            case Held<AccountRecord> held -> present(map, state, held, paying);
            case Read.Missing<AccountRecord> missing -> send(map.cursor(ACTIDIN), NOT_FOUND, state);
            case Read.Failed<AccountRecord> failed -> send(map.cursor(ACTIDIN), "Unable to lookup Account...", state);
        };
    }

    private Outcome present(SymbolicMap<COBIL0A> map, State state, Held<AccountRecord> held, boolean paying) {
        AccountRecord account = held.record();
        map.set(CURBAL, edited(account.currBal()));
        if (account.currBal().signum() <= 0) {
            return send(map.cursor(ACTIDIN), "You have nothing to pay...", state);
        }
        if (!paying) {
            return send(map.cursor(CONFIRM), "Confirm to make a bill payment...", state);
        }
        return pay(map, state, held);
    }

    @LegacyDefect(id = "COBIL00C-pays-without-xref",
            goldens = {"bill-pay-no-xref-still-pays"},
            behavior = "CXACAIX NOTFND is ignored, the payment is written with a LOW-VALUES card number, "
                    + "and the success message replaces the account-not-found error",
            fix = "stop when the account has no card cross-reference and leave the balance unchanged")
    private Outcome pay(SymbolicMap<COBIL0A> map, State state, Held<AccountRecord> held) {
        AccountRecord account = held.record();
        String card = switch (xrefs.findByAccount(account.id())) {
            case Read.Found<XrefRecord> found -> found.record().cardNum();
            case Read.Missing<XrefRecord> missing -> null;
            case Read.Failed<XrefRecord> failed -> null;
        };
        BigDecimal amount = tranAmount(account.currBal());
        String tranId = nextTranId();
        String timestamp = timestamp();
        TransactionStore.Insert wrote = transactions.write(new TransactionRecord(tranId, "02", 2, "POS TERM",
                "BILL PAYMENT - ONLINE", amount, 999999999, "BILL PAYMENT", "N/A", "N/A", card, timestamp, timestamp));
        BigDecimal next = newBalance(account.currBal(), amount);
        if (accounts.rewrite(held, withBalance(account, next)) != Write.DONE) {
            return send(map.cursor(ACTIDIN), "Unable to Update Account...", state);
        }
        return switch (wrote) {
            case INSERTED -> send(cleared(map).color(ERRMSG, Color.GREEN),
                    "Payment successful.  Your Transaction ID is " + tranId + ".", state);
            case DUPLICATE -> send(map.cursor(ACTIDIN), "Tran ID already exist...", state);
            case FAILED -> send(map.cursor(ACTIDIN), "Unable to Add Bill pay Transaction...", state);
        };
    }

    @LegacyDefect(id = "COBIL00C-short-account-unpadded",
            goldens = {"bill-pay-short-account-not-found"},
            behavior = "MOVE of a short account id into ACCT-ID PIC 9(11) leaves it unpadded, so 1 is not account 00000000001",
            fix = "zero-pad a short numeric account id to 11 digits before the read")
    private static Optional<Long> accountId(String typed) {
        return ELEVEN_DIGITS.matcher(typed).matches() ? Optional.of(Long.parseLong(typed)) : Optional.empty();
    }

    private String nextTranId() {
        KeyedBrowse<TransactionRecord> browse = transactions.browse();
        if (browse.start(KeyedBrowse.StartKey.HIGH_VALUES) != KeyedBrowse.Start.NORMAL) {
            throw new IllegalStateException("STARTBR TRANSACT");
        }
        try {
            long previous = switch (browse.previous()) {
                case KeyedBrowse.Read.Normal<TransactionRecord> found -> Long.parseLong(found.record().id());
                case KeyedBrowse.Read.EndFile<TransactionRecord> end -> 0L;
                case KeyedBrowse.Read.Failed<TransactionRecord> failed ->
                        throw new IllegalStateException("READPREV TRANSACT");
            };
            return "%016d".formatted(previous + 1);
        } finally {
            browse.end();
        }
    }

    /** MOVE of ACCT-CURR-BAL into TRAN-AMT PIC S9(09)V99 drops integer digits above nine. */
    private static BigDecimal tranAmount(BigDecimal balance) {
        return balance.remainder(TRAN_AMT_MODULUS).setScale(2, RoundingMode.DOWN);
    }

    /** COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT into PIC S9(10)V99. */
    private static BigDecimal newBalance(BigDecimal balance, BigDecimal amount) {
        return balance.subtract(amount).setScale(2, RoundingMode.DOWN).remainder(BALANCE_MODULUS).setScale(2);
    }

    /** PIC +9999999999.99, the picture CURBAL is filled from. */
    private static String edited(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.DOWN);
        String digits = scaled.abs().movePointRight(2).toBigInteger().toString();
        digits = digits.length() > 12 ? digits.substring(digits.length() - 12) : "0".repeat(12 - digits.length()) + digits;
        return (scaled.signum() < 0 ? "-" : "+") + digits.substring(0, 10) + "." + digits.substring(10);
    }

    /** GET-CURRENT-TIMESTAMP: FORMATTIME date and time, then microseconds forced to zero. */
    private String timestamp() {
        return LocalDateTime.now(clock).format(TIMESTAMP) + ".000000";
    }

    private static Confirm confirm(String text) {
        return switch (text) {
            case "Y", "y" -> Confirm.YES;
            case "N", "n" -> Confirm.NO;
            case "" -> Confirm.BLANK;
            default -> Confirm.OTHER;
        };
    }

    private static AccountRecord withBalance(AccountRecord account, BigDecimal balance) {
        return new AccountRecord(account.id(), account.activeStatus(), balance, account.creditLimit(),
                account.cashCreditLimit(), account.openDate(), account.expirationDate(), account.reissueDate(),
                account.currCycCredit(), account.currCycDebit(), account.addrZip(), account.groupId(), account.filler());
    }

    private static SymbolicMap<COBIL0A> cleared(SymbolicMap<COBIL0A> map) {
        return map.cursor(ACTIDIN).blank(ACTIDIN, CURBAL, CONFIRM);
    }

    private Outcome send(SymbolicMap<COBIL0A> map, String message, State state) {
        return show(map.set(ERRMSG, message), state);
    }
}
