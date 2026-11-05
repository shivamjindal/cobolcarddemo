package com.carddemo.transaction;

import static com.carddemo.online.maps.COTRN1A.CARDNUM;
import static com.carddemo.online.maps.COTRN1A.ERRMSG;
import static com.carddemo.online.maps.COTRN1A.MCITY;
import static com.carddemo.online.maps.COTRN1A.MID;
import static com.carddemo.online.maps.COTRN1A.MNAME;
import static com.carddemo.online.maps.COTRN1A.MZIP;
import static com.carddemo.online.maps.COTRN1A.TCATCD;
import static com.carddemo.online.maps.COTRN1A.TDESC;
import static com.carddemo.online.maps.COTRN1A.TORIGDT;
import static com.carddemo.online.maps.COTRN1A.TPROCDT;
import static com.carddemo.online.maps.COTRN1A.TRNAMT;
import static com.carddemo.online.maps.COTRN1A.TRNID;
import static com.carddemo.online.maps.COTRN1A.TRNIDIN;
import static com.carddemo.online.maps.COTRN1A.TRNSRC;
import static com.carddemo.online.maps.COTRN1A.TTYPCD;

import org.springframework.stereotype.Component;

import com.carddemo.accounts.Read;
import com.carddemo.menu.MainMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.Key;
import com.carddemo.online.Outcome;
import com.carddemo.online.ProgramName;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COTRN1A;
import com.carddemo.transactions.TransactionRecord;
import com.carddemo.transactions.TransactionStore;

/** COTRN01C. PF3 returns to CDEMO-FROM-PROGRAM, the list after a selection and the main menu otherwise. */
@Component
public final class TransactionViewScreen
        extends Screen<COTRN1A, TransactionViewScreen.Action, TransactionViewScreen.State, TransactionSelected> {

    public static final ScreenRef<TransactionSelected> REF =
            ScreenRef.of("COTRN01C", "CT01", "transaction-view", TransactionSelected.class);

    enum Action implements ScreenAction {
        FETCH(Key.ENTER), BACK(Key.PF3), CLEAR(Key.PF4), BROWSE(Key.PF5);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /** @param returnTo CDEMO-FROM-PROGRAM, where PF3 goes */
    record State(ProgramName returnTo) implements ScreenState {}

    private final TransactionStore transactions;

    TransactionViewScreen(TransactionStore transactions) {
        super(REF, COTRN1A.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.transactions = transactions;
    }

    @Override
    protected Outcome enter(Arrival<TransactionSelected> arrival) {
        State state = new State(arrival.from().orElse(MainMenuScreen.REF.program()));
        SymbolicMap<COTRN1A> map = SymbolicMap.lowValues(COTRN1A.MAP).cursor(TRNIDIN);
        return arrival.handoff()
                .map(selected -> processEnterKey(map.set(TRNIDIN, selected.id()), state))
                .orElseGet(() -> showView(map, "", state));
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COTRN1A> map, State state) {
        return switch (action) {
            case FETCH -> processEnterKey(map, state);
            case BACK -> transfer(state.returnTo());
            case CLEAR -> showView(map.blank(TRNIDIN, TRNID, CARDNUM, TTYPCD, TCATCD, TRNSRC, TRNAMT, TDESC, TORIGDT,
                    TPROCDT, MID, MNAME, MCITY, MZIP), "", state);
            case BROWSE -> transfer(TransactionListScreen.REF.program());
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COTRN1A> map, State state) {
        return showView(map, CommonMessages.INVALID_KEY, state);
    }

    private Outcome processEnterKey(SymbolicMap<COTRN1A> map, State state) {
        map.cursor(TRNIDIN);
        if (map.isBlank(TRNIDIN)) {
            return showView(map, "Tran ID can NOT be empty...", state);
        }
        map.blank(TRNID, CARDNUM, TTYPCD, TCATCD, TRNSRC, TRNAMT, TDESC, TORIGDT, TPROCDT, MID, MNAME, MCITY, MZIP);
        return switch (transactions.find(map.text(TRNIDIN))) {
            case Read.Found<TransactionRecord> found -> showTran(map, found.record(), state);
            case Read.Missing<TransactionRecord> missing -> showView(map, "Transaction ID NOT found...", state);
            case Read.Failed<TransactionRecord> failed -> showView(map, "Unable to lookup Transaction...", state);
        };
    }

    private Outcome showTran(SymbolicMap<COTRN1A> map, TransactionRecord tran, State state) {
        map.set(TRNID, tran.id())
                .set(TTYPCD, tran.typeCd())
                .set(TCATCD, TransactionPicture.category(tran.catCd()))
                .set(TRNSRC, tran.source())
                .set(TRNAMT, TransactionPicture.amount(tran.amount()))
                .set(TDESC, tran.desc())
                .set(TORIGDT, tran.origTs())
                .set(TPROCDT, tran.procTs())
                .set(MID, TransactionPicture.merchantId(tran.merchantId()))
                .set(MNAME, tran.merchantName())
                .set(MCITY, tran.merchantCity())
                .set(MZIP, tran.merchantZip());
        if (tran.cardNum() != null) {
            map.set(CARDNUM, tran.cardNum());
        }
        return showView(map, "", state);
    }

    private Outcome showView(SymbolicMap<COTRN1A> map, String message, State state) {
        return show(map.cursor(TRNIDIN).set(ERRMSG, message), state);
    }
}
