package com.carddemo.authorization;

import static com.carddemo.online.maps.COPAU1A.AUTHAMT;
import static com.carddemo.online.maps.COPAU1A.AUTHCD;
import static com.carddemo.online.maps.COPAU1A.AUTHDT;
import static com.carddemo.online.maps.COPAU1A.AUTHFRD;
import static com.carddemo.online.maps.COPAU1A.AUTHMTC;
import static com.carddemo.online.maps.COPAU1A.AUTHRSN;
import static com.carddemo.online.maps.COPAU1A.AUTHRSP;
import static com.carddemo.online.maps.COPAU1A.AUTHSRC;
import static com.carddemo.online.maps.COPAU1A.AUTHTM;
import static com.carddemo.online.maps.COPAU1A.AUTHTYP;
import static com.carddemo.online.maps.COPAU1A.CARDNUM;
import static com.carddemo.online.maps.COPAU1A.CRDEXP;
import static com.carddemo.online.maps.COPAU1A.ERRMSG;
import static com.carddemo.online.maps.COPAU1A.MCCCD;
import static com.carddemo.online.maps.COPAU1A.MERCITY;
import static com.carddemo.online.maps.COPAU1A.MERID;
import static com.carddemo.online.maps.COPAU1A.MERNAME;
import static com.carddemo.online.maps.COPAU1A.MERST;
import static com.carddemo.online.maps.COPAU1A.MERZIP;
import static com.carddemo.online.maps.COPAU1A.POSEMD;
import static com.carddemo.online.maps.COPAU1A.TRNID;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.cics.Syncpoint;
import com.carddemo.dli.Dbpautp0;
import com.carddemo.dli.DliStatus;
import com.carddemo.dli.Get;
import com.carddemo.dli.Pcb;
import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;
import com.carddemo.dli.Ssa;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.Key;
import com.carddemo.online.Outcome;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SendMode;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COPAU1A;

/**
 * COPAUS1C, one pending authorization. PF8 moves to the account's next one, PF5 reports it as fraud or removes
 * the report through COPAUS2C and flags the IMS detail, PF3 returns to the summary.
 */
@Component
public final class PendingAuthDetailScreen
        extends Screen<COPAU1A, PendingAuthDetailScreen.Action, PendingAuthDetailScreen.State, PendingAuthContext> {

    public static final ScreenRef<PendingAuthContext> REF =
            ScreenRef.of("COPAUS1C", "CPVD", "pending-auth-detail", PendingAuthContext.class);

    enum Action implements ScreenAction {
        REFRESH(Key.ENTER), BACK(Key.PF3), FRAUD(Key.PF5), NEXT(Key.PF8);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    record State(Long acctId, int custId, AuthorizationPaging paging) implements ScreenState {}

    /** WS-DECLINE-REASON-TABLE. */
    private static final Map<String, String> DECLINE_REASONS = Map.of(
            "0000", "APPROVED",
            "3100", "INVALID CARD",
            "4100", "INSUFFICNT FUND",
            "4200", "CARD NOT ACTIVE",
            "4300", "ACCOUNT CLOSED",
            "4400", "EXCED DAILY LMT",
            "5100", "CARD FRAUD",
            "5200", "MERCHANT FRAUD",
            "5300", "LOST CARD",
            "9000", "UNKNOWN");

    private final Dbpautp0 database;
    private final FraudRegister fraudRegister;
    private final Syncpoint syncpoint;

    PendingAuthDetailScreen(Dbpautp0 database, FraudRegister fraudRegister, Syncpoint syncpoint) {
        super(REF, COPAU1A.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.database = database;
        this.fraudRegister = fraudRegister;
        this.syncpoint = syncpoint;
    }

    /** Without a COMMAREA it XCTLs straight to the summary. */
    @Override
    protected Outcome enter(Arrival<PendingAuthContext> arrival) {
        if (arrival.handoff().isEmpty()) {
            return transfer(PendingAuthSummaryScreen.REF, new PendingAuthContext(null, 0, new AuthorizationPaging()));
        }
        PendingAuthContext context = arrival.handoff().get();
        Task task = new Task(SymbolicMap.lowValues(COPAU1A.MAP),
                new State(context.acctId(), context.custId(), context.paging()));
        task.processEnterKey();
        return task.send();
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COPAU1A> map, State state) {
        Task task = new Task(map, state);
        return switch (action) {
            case REFRESH -> {
                task.processEnterKey();
                yield task.send();
            }
            case BACK -> transfer(PendingAuthSummaryScreen.REF,
                    new PendingAuthContext(state.acctId(), state.custId(), state.paging().copy()));
            case FRAUD -> {
                task.markAuthFraud();
                yield task.send();
            }
            case NEXT -> {
                task.processPf8Key();
                yield task.send();
            }
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COPAU1A> map, State state) {
        Task task = new Task(map, state);
        task.processEnterKey();
        task.message.replace(CommonMessages.INVALID_KEY);
        return task.send();
    }

    /** One run of the program: its working storage and the COMMAREA. */
    private final class Task {
        private SymbolicMap<COPAU1A> map;
        private final Long acctId;
        private final int custId;
        private final AuthorizationPaging paging;
        private final WsMessage message = new WsMessage();
        private boolean error;
        private boolean endOfAuths;
        private SendMode mode = SendMode.ERASE_CURSOR;
        private Pcb pcb;
        private Optional<PendingAuthDetail> detail = Optional.empty();

        Task(SymbolicMap<COPAU1A> map, State state) {
            this.map = map;
            this.acctId = state.acctId();
            this.custId = state.custId();
            this.paging = state.paging().copy();
        }

        void processEnterKey() {
            map = SymbolicMap.lowValues(COPAU1A.MAP);
            if (acctId != null && paging.selected != null) {
                readAuthRecord();
                syncpoint.commit();
            } else {
                error = true;
            }
            populateAuthDetails();
        }

        void markAuthFraud() {
            readAuthRecord();
            if (detail.isEmpty()) {
                return;
            }
            boolean confirmed = "F".equals(detail.get().authFraud());
            String action = confirmed ? "R" : "F";
            detail = Optional.of(FraudRegister.withFraud(detail.get(), action));
            FraudRegister.Tagged tagged = fraudRegister.tag(acctId, custId, detail.get(), action);
            if (tagged.success()) {
                updateAuthDetails(tagged.detail());
            } else {
                message.replace(tagged.message());
                syncpoint.rollback();
            }
            paging.selected = detail.get().key();
            populateAuthDetails();
        }

        void processPf8Key() {
            readAuthRecord();
            readNextAuthRecord();
            syncpoint.commit();
            if (endOfAuths) {
                mode = SendMode.CURSOR;
                message.replace("Already at the last Authorization...");
            } else {
                paging.selected = detail.orElseThrow().key();
                populateAuthDetails();
            }
        }

        /** SCHD, the account's summary, then the selected authorization under it. */
        private void readAuthRecord() {
            pcb = database.schedule();
            Get<PendingAuthSummary> summary = pcb.gu(Ssa.summary(acctId == null ? 0 : acctId));
            endOfAuths = status(summary.status(), " System error while reading Auth Summary: Code:");
            if (!endOfAuths) {
                Ssa.Detail ssa = paging.selected == null ? Ssa.detail() : Ssa.detail(paging.selected);
                Get<PendingAuthDetail> got = pcb.gnp(ssa);
                endOfAuths = status(got.status(), " System error while reading Auth Details: Code:");
                detail = got.segment().or(() -> detail);
            }
        }

        private void readNextAuthRecord() {
            Get<PendingAuthDetail> got = pcb.gnp(Ssa.detail());
            endOfAuths = status(got.status(), " System error while reading next Auth: Code:");
            detail = got.segment().or(() -> detail);
        }

        /** Whether the call ended the authorizations; any status but GE and GB is an error. */
        private boolean status(DliStatus status, String failure) {
            return switch (status) {
                case OK -> false;
                case GE, GB -> true;
                default -> {
                    error = true;
                    message.overlay(failure + status.code());
                    yield endOfAuths;
                }
            };
        }

        private void updateAuthDetails(PendingAuthDetail tagged) {
            detail = Optional.of(tagged);
            DliStatus replaced = pcb.repl(tagged);
            if (replaced == DliStatus.OK) {
                syncpoint.commit();
                message.replace("R".equals(tagged.authFraud()) ? "AUTH FRAUD REMOVED..." : "AUTH MARKED FRAUD...");
            } else {
                syncpoint.rollback();
                error = true;
                message.overlay(" System error while FRAUD Tagging, ROLLBACK||" + replaced.code());
            }
        }

        private void populateAuthDetails() {
            if (error || detail.isEmpty()) {
                return;
            }
            PendingAuthDetail auth = detail.get();
            boolean approved = "00".equals(auth.authRespCode());
            String reason = AuthPicture.pad(auth.authRespReason(), 4);
            String description = DECLINE_REASONS.get(reason);
            map.set(CARDNUM, auth.cardNum())
                    .set(AUTHDT, AuthPicture.monthDayYear(auth.authOrigDate()))
                    .set(AUTHTM, AuthPicture.hourMinuteSecond(auth.authOrigTime()))
                    .set(AUTHAMT, AuthPicture.signed(auth.approvedAmt(), 8))
                    .set(AUTHRSP, approved ? "A" : "D")
                    .color(AUTHRSP, approved ? Color.GREEN : Color.RED)
                    .set(AUTHRSN, description == null ? "9999-ERROR" : reason + "-" + description);
            showAuthorizationCode(auth);
            String expiry = AuthPicture.pad(auth.cardExpiryDate(), 4);
            map.set(POSEMD, "%02d".formatted(auth.posEntryMode()))
                    .set(AUTHSRC, auth.messageSource())
                    .set(MCCCD, auth.merchantCatagoryCode())
                    .set(CRDEXP, expiry.substring(0, 2) + "/" + expiry.substring(2, 4))
                    .set(AUTHTYP, auth.authType())
                    .set(TRNID, auth.transactionId())
                    .set(AUTHMTC, auth.matchStatus())
                    .set(AUTHFRD, "F".equals(auth.authFraud()) || "R".equals(auth.authFraud())
                            ? auth.authFraud() + "-" + AuthPicture.pad(auth.fraudRptDate(), 8)
                            : "-")
                    .set(MERNAME, auth.merchantName())
                    .set(MERID, auth.merchantId())
                    .set(MERCITY, auth.merchantCity())
                    .set(MERST, auth.merchantState())
                    .set(MERZIP, auth.merchantZip());
        }

        @LegacyDefect(id = "COPAUS1C-authcd-shows-processing-code",
                goldens = {"pending-auth-detail-display"},
                behavior = "the Auth Code field shows PA-PROCESSING-CODE, not the authorization id code "
                        + "PA-AUTH-ID-CODE the label names",
                fix = "move PA-AUTH-ID-CODE to AUTHCDO")
        private void showAuthorizationCode(PendingAuthDetail auth) {
            map.set(AUTHCD, "%06d".formatted(auth.processingCode()));
        }

        /** SEND-AUTHVIEW-SCREEN. */
        Outcome send() {
            map.set(ERRMSG, message.toString()).cursor(CARDNUM);
            return show(map, mode, new State(acctId, custId, paging));
        }
    }
}
