package com.carddemo.transaction;

import static com.carddemo.online.maps.COTRN2A.ACTIDIN;
import static com.carddemo.online.maps.COTRN2A.CARDNIN;
import static com.carddemo.online.maps.COTRN2A.CONFIRM;
import static com.carddemo.online.maps.COTRN2A.ERRMSG;
import static com.carddemo.online.maps.COTRN2A.MCITY;
import static com.carddemo.online.maps.COTRN2A.MID;
import static com.carddemo.online.maps.COTRN2A.MNAME;
import static com.carddemo.online.maps.COTRN2A.MZIP;
import static com.carddemo.online.maps.COTRN2A.TCATCD;
import static com.carddemo.online.maps.COTRN2A.TDESC;
import static com.carddemo.online.maps.COTRN2A.TORIGDT;
import static com.carddemo.online.maps.COTRN2A.TPROCDT;
import static com.carddemo.online.maps.COTRN2A.TRNAMT;
import static com.carddemo.online.maps.COTRN2A.TRNSRC;
import static com.carddemo.online.maps.COTRN2A.TTYPCD;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.accounts.CardXrefStore;
import com.carddemo.accounts.Read;
import com.carddemo.accounts.XrefRecord;
import com.carddemo.dates.DateCheck;
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
import com.carddemo.online.maps.COTRN2A;
import com.carddemo.transactions.TransactionRecord;
import com.carddemo.transactions.TransactionStore;

/**
 * COTRN02C. ENTER edits the key fields, then the data fields, and adds the transaction when CONFIRM is
 * Y. Every failed edit sends the screen and returns, so the first one wins. PF5 copies the data fields
 * of the highest-keyed TRANSACT record and then does what ENTER does.
 */
@Component
public final class TransactionAddScreen extends Screen<COTRN2A, TransactionAddScreen.Action, TransactionAddScreen.State, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COTRN02C", "CT02", "transaction-add", NoHandoff.class);

    enum Action implements ScreenAction {
        ADD(Key.ENTER), BACK(Key.PF3), CLEAR(Key.PF4), COPY_LAST(Key.PF5);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /** @param returnTo CDEMO-FROM-PROGRAM, where PF3 goes; the COMMAREA keeps it across turns */
    record State(ProgramName returnTo) implements ScreenState {}

    /** A failed edit: its message, with the cursor on its field. */
    private record Rejection(COTRN2A field, String message) {}

    private record Edit(COTRN2A field, Predicate<String> passes, String message) {}

    private static final String DATE_FORMAT = "YYYY-MM-DD";
    private static final Pattern AMOUNT = Pattern.compile("[+-]\\d{8}\\.\\d{2}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Predicate<String> ENTERED = text -> !text.isEmpty();

    /** The edits before the amount is reformatted, in the order VALIDATE-INPUT-DATA-FIELDS makes them. */
    private static final List<Edit> PRESENCE_AND_FORMAT = List.of(
            new Edit(TTYPCD, ENTERED, "Type CD can NOT be empty..."),
            new Edit(TCATCD, ENTERED, "Category CD can NOT be empty..."),
            new Edit(TRNSRC, ENTERED, "Source can NOT be empty..."),
            new Edit(TDESC, ENTERED, "Description can NOT be empty..."),
            new Edit(TRNAMT, ENTERED, "Amount can NOT be empty..."),
            new Edit(TORIGDT, ENTERED, "Orig Date can NOT be empty..."),
            new Edit(TPROCDT, ENTERED, "Proc Date can NOT be empty..."),
            new Edit(MID, ENTERED, "Merchant ID can NOT be empty..."),
            new Edit(MNAME, ENTERED, "Merchant Name can NOT be empty..."),
            new Edit(MCITY, ENTERED, "Merchant City can NOT be empty..."),
            new Edit(MZIP, ENTERED, "Merchant Zip can NOT be empty..."),
            new Edit(TTYPCD, numeric(TTYPCD), "Type CD must be Numeric..."),
            new Edit(TCATCD, numeric(TCATCD), "Category CD must be Numeric..."),
            new Edit(TRNAMT, AMOUNT.asMatchPredicate(), "Amount should be in format -99999999.99"),
            new Edit(TORIGDT, DATE.asMatchPredicate(), "Orig Date should be in format YYYY-MM-DD"),
            new Edit(TPROCDT, DATE.asMatchPredicate(), "Proc Date should be in format YYYY-MM-DD"));

    private static final List<Edit> CALENDAR_AND_MERCHANT = List.of(
            new Edit(TORIGDT, TransactionAddScreen::validDate, "Orig Date - Not a valid date..."),
            new Edit(TPROCDT, TransactionAddScreen::validDate, "Proc Date - Not a valid date..."),
            new Edit(MID, numeric(MID), "Merchant ID must be Numeric..."));

    /** TRAN-RECORD as working storage starts it, which a READPREV at end of file leaves in place. */
    private static final TransactionRecord INITIALIZED =
            new TransactionRecord("0".repeat(16), "", 0, "", "", BigDecimal.ZERO, 0, "", "", "", "", "", "");

    private final CardXrefStore xrefs;
    private final TransactionStore transactions;

    TransactionAddScreen(CardXrefStore xrefs, TransactionStore transactions) {
        super(REF, COTRN2A.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.xrefs = xrefs;
        this.transactions = transactions;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return sendTrnaddScreen(SymbolicMap.lowValues(COTRN2A.MAP).cursor(ACTIDIN), "",
                new State(arrival.from().orElse(MainMenuScreen.REF.program())));
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COTRN2A> map, State state) {
        return switch (action) {
            case ADD -> processEnterKey(map, state);
            case BACK -> transfer(state.returnTo());
            case CLEAR -> sendTrnaddScreen(initializeAllFields(map), "", state);
            case COPY_LAST -> copyLastTranData(map, state);
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COTRN2A> map, State state) {
        return sendTrnaddScreen(map, CommonMessages.INVALID_KEY, state);
    }

    private Outcome processEnterKey(SymbolicMap<COTRN2A> map, State state) {
        Optional<Rejection> rejected = validateKeyFields(map).or(() -> validateDataFields(map));
        if (rejected.isPresent()) {
            return reject(map, rejected.get(), state);
        }
        return switch (map.text(CONFIRM)) {
            case "Y", "y" -> addTransaction(map, state);
            case "N", "n" -> confirmNo(map, state);
            case "" -> askToConfirm(map, state);
            default -> reject(map, new Rejection(CONFIRM, "Invalid value. Valid values are (Y/N)..."), state);
        };
    }

    @LegacyDefect(id = "COTRN02C-confirm-n-reprompts", goldens = "transaction-add-confirm-n",
            behavior = "confirm N does not cancel the add; it asks to confirm again, as a blank confirm does",
            fix = "clear the screen and say the transaction was not added")
    private Outcome confirmNo(SymbolicMap<COTRN2A> map, State state) {
        return askToConfirm(map, state);
    }

    private Outcome askToConfirm(SymbolicMap<COTRN2A> map, State state) {
        return reject(map, new Rejection(CONFIRM, "Confirm to add this transaction..."), state);
    }

    private Optional<Rejection> validateKeyFields(SymbolicMap<COTRN2A> map) {
        if (!map.isBlank(ACTIDIN)) {
            return byAccount(map);
        }
        if (!map.isBlank(CARDNIN)) {
            return byCard(map);
        }
        return rejection(ACTIDIN, "Account or Card Number must be entered...");
    }

    @LegacyDefect(id = "COTRN02C-account-replaces-card", goldens = "transaction-add-account-wins-over-card",
            behavior = "when both an account and a card are typed, only the account is looked up, and the typed card "
                    + "is replaced by the account's card from the cross-reference, so the transaction posts to a card "
                    + "the user did not type",
            fix = "reject a typed card that does not belong to the typed account")
    private Optional<Rejection> byAccount(SymbolicMap<COTRN2A> map) {
        if (!numeric(ACTIDIN).test(map.text(ACTIDIN))) {
            return rejection(ACTIDIN, "Account ID must be Numeric...");
        }
        return switch (xrefs.findByAccount(Long.parseLong(map.text(ACTIDIN)))) {
            case Read.Found<XrefRecord> found -> {
                map.set(CARDNIN, found.record().cardNum());
                yield Optional.empty();
            }
            case Read.Missing<XrefRecord> missing -> rejection(ACTIDIN, "Account ID NOT found...");
            case Read.Failed<XrefRecord> failed -> rejection(ACTIDIN, "Unable to lookup Acct in XREF AIX file...");
        };
    }

    private Optional<Rejection> byCard(SymbolicMap<COTRN2A> map) {
        if (!numeric(CARDNIN).test(map.text(CARDNIN))) {
            return rejection(CARDNIN, "Card Number must be Numeric...");
        }
        return switch (xrefs.find(map.text(CARDNIN))) {
            case Read.Found<XrefRecord> found -> {
                map.set(ACTIDIN, "%011d".formatted(found.record().acctId()));
                yield Optional.empty();
            }
            case Read.Missing<XrefRecord> missing -> rejection(CARDNIN, "Card Number NOT found...");
            case Read.Failed<XrefRecord> failed -> rejection(CARDNIN, "Unable to lookup Card # in XREF file...");
        };
    }

    private static Optional<Rejection> validateDataFields(SymbolicMap<COTRN2A> map) {
        return firstFailed(PRESENCE_AND_FORMAT, map).or(() -> {
            map.set(TRNAMT, TransactionPicture.amount(amount(map)));
            return firstFailed(CALENDAR_AND_MERCHANT, map);
        });
    }

    private static Optional<Rejection> firstFailed(List<Edit> edits, SymbolicMap<COTRN2A> map) {
        return edits.stream()
                .filter(edit -> !edit.passes().test(map.text(edit.field())))
                .findFirst()
                .map(edit -> new Rejection(edit.field(), edit.message()));
    }

    @LegacyDefect(id = "COTRN02C-short-field-not-numeric",
            goldens = {"transaction-add-short-account-not-numeric", "transaction-add-type-one-digit"},
            behavior = "IS NUMERIC fails when trailing blanks pad a short entry out to the field, so an account id "
                    + "typed without its leading zeros, or a one-digit type code, is not numeric",
            fix = "accept digits shorter than the field and pad them with leading zeros")
    private static Predicate<String> numeric(COTRN2A field) {
        return text -> text.length() == field.length() && text.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    @LegacyDefect(id = "COTRN02C-pre-1582-accepted", goldens = "transaction-add-orig-date-pre-1582-accepted",
            behavior = "a date before 15 October 1582 passes, because CSUTLDTC's message 2513 (unsupported range) "
                    + "is ignored",
            fix = "reject message 2513 like every other non-zero severity")
    private static boolean validDate(String date) {
        DateCheck.Feedback feedback = DateCheck.check(date, DATE_FORMAT);
        return feedback.valid() || feedback.msgNo() == DateCheck.UNSUPPORTED_RANGE;
    }

    private Outcome addTransaction(SymbolicMap<COTRN2A> map, State state) {
        return withLastTransaction(map, state, last -> {
            String id = "%016d".formatted(Long.parseLong(last.id()) + 1);
            return switch (transactions.write(newTransaction(id, map))) {
                case INSERTED -> sendTrnaddScreen(initializeAllFields(map).color(ERRMSG, Color.GREEN),
                        "Transaction added successfully.  Your Tran ID is " + id + ".", state);
                case DUPLICATE -> reject(map, new Rejection(ACTIDIN, "Tran ID already exist..."), state);
                case FAILED -> reject(map, new Rejection(ACTIDIN, "Unable to Add Transaction..."), state);
            };
        });
    }

    private static TransactionRecord newTransaction(String id, SymbolicMap<COTRN2A> map) {
        return new TransactionRecord(id, map.text(TTYPCD), Integer.parseInt(map.text(TCATCD)), map.text(TRNSRC),
                map.text(TDESC), amount(map), Integer.parseInt(map.text(MID)), map.text(MNAME), map.text(MCITY),
                map.text(MZIP), map.text(CARDNIN), timestamp(map.text(TORIGDT)), timestamp(map.text(TPROCDT)));
    }

    @LegacyDefect(id = "COTRN02C-bare-date-timestamps", goldens = "transaction-add-confirm-y-writes",
            behavior = "TRAN-ORIG-TS and TRAN-PROC-TS, 26-character timestamps, get the bare 10-character typed date",
            fix = "write the date as a full timestamp, yyyy-MM-dd HH:mm:ss.SSSSSS, as COBIL00C does")
    private static String timestamp(String typedDate) {
        return typedDate;
    }

    @LegacyDefect(id = "COTRN02C-copy-last-any-account",
            goldens = {"transaction-add-pf5-copy-last", "transaction-add-pf5-copy-last-confirm"},
            behavior = "PF5 copies the highest-keyed TRANSACT record whatever its account, not the last transaction "
                    + "of the typed account or card",
            fix = "browse the transactions of the typed card and copy its latest")
    private Outcome copyLastTranData(SymbolicMap<COTRN2A> map, State state) {
        Optional<Rejection> rejected = validateKeyFields(map);
        if (rejected.isPresent()) {
            return reject(map, rejected.get(), state);
        }
        return withLastTransaction(map, state, last -> {
            map.set(TTYPCD, last.typeCd())
                    .set(TCATCD, TransactionPicture.category(last.catCd()))
                    .set(TRNSRC, last.source())
                    .set(TRNAMT, TransactionPicture.amount(last.amount()))
                    .set(TDESC, last.desc())
                    .set(TORIGDT, last.origTs())
                    .set(TPROCDT, last.procTs())
                    .set(MID, TransactionPicture.merchantId(last.merchantId()))
                    .set(MNAME, last.merchantName())
                    .set(MCITY, last.merchantCity())
                    .set(MZIP, last.merchantZip());
            return processEnterKey(map, state);
        });
    }

    /** STARTBR at HIGH-VALUES, READPREV, ENDBR: the highest-keyed record, or TRAN-ID zeros at end of file. */
    private Outcome withLastTransaction(SymbolicMap<COTRN2A> map, State state, Function<TransactionRecord, Outcome> then) {
        KeyedBrowse<TransactionRecord> browse = transactions.browse();
        switch (browse.start(KeyedBrowse.StartKey.HIGH_VALUES)) {
            case NORMAL -> {
            }
            case NOTFND -> {
                return reject(map, new Rejection(ACTIDIN, "Transaction ID NOT found..."), state);
            }
            case FAILED -> {
                return reject(map, new Rejection(ACTIDIN, "Unable to lookup Transaction..."), state);
            }
        }
        TransactionRecord last;
        switch (browse.previous()) {
            case KeyedBrowse.Read.Normal<TransactionRecord> normal -> last = normal.record();
            case KeyedBrowse.Read.EndFile<TransactionRecord> endFile -> last = INITIALIZED;
            case KeyedBrowse.Read.Failed<TransactionRecord> failed -> {
                return reject(map, new Rejection(ACTIDIN, "Unable to lookup Transaction..."), state);
            }
        }
        browse.end();
        return then.apply(last);
    }

    /** {@code FUNCTION NUMVAL-C} of an amount that passed the format edit, which leaves only [+-]99999999.99. */
    private static BigDecimal amount(SymbolicMap<COTRN2A> map) {
        return new BigDecimal(map.text(TRNAMT));
    }

    private static Optional<Rejection> rejection(COTRN2A field, String message) {
        return Optional.of(new Rejection(field, message));
    }

    private static SymbolicMap<COTRN2A> initializeAllFields(SymbolicMap<COTRN2A> map) {
        return map.cursor(ACTIDIN).blank(ACTIDIN, CARDNIN, TTYPCD, TCATCD, TRNSRC, TRNAMT, TDESC, TORIGDT, TPROCDT,
                MID, MNAME, MCITY, MZIP, CONFIRM);
    }

    private Outcome reject(SymbolicMap<COTRN2A> map, Rejection rejection, State state) {
        return sendTrnaddScreen(map.cursor(rejection.field()), rejection.message(), state);
    }

    private Outcome sendTrnaddScreen(SymbolicMap<COTRN2A> map, String message, State state) {
        return show(map.set(ERRMSG, message), state);
    }
}
