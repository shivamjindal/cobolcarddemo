package com.carddemo.report;

import static com.carddemo.online.maps.CORPT0A.CONFIRM;
import static com.carddemo.online.maps.CORPT0A.CUSTOM;
import static com.carddemo.online.maps.CORPT0A.EDTDD;
import static com.carddemo.online.maps.CORPT0A.EDTMM;
import static com.carddemo.online.maps.CORPT0A.EDTYYYY;
import static com.carddemo.online.maps.CORPT0A.ERRMSG;
import static com.carddemo.online.maps.CORPT0A.MONTHLY;
import static com.carddemo.online.maps.CORPT0A.SDTDD;
import static com.carddemo.online.maps.CORPT0A.SDTMM;
import static com.carddemo.online.maps.CORPT0A.SDTYYYY;
import static com.carddemo.online.maps.CORPT0A.YEARLY;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.account.Intrinsics;
import com.carddemo.dates.DateCheck;
import com.carddemo.jobs.JobSubmissions;
import com.carddemo.jobs.SubmittedJob;
import com.carddemo.menu.MainMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.Outcome;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.CORPT0A;
import com.carddemo.report.ReportDates.Range;
import com.carddemo.users.UserId;

/** CORPT00C. Ask which transaction report to print, then write its job to the JOBS queue. */
@Component
public final class ReportSubmitScreen extends Screen<CORPT0A, ReportSubmitScreen.Action, ReportSubmitScreen.State, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("CORPT00C", "CR00", "report-submit", NoHandoff.class);

    private static final String PICTURE = "YYYY-MM-DD";

    private record DatePart(CORPT0A field, int digits, String limit, String empty, String invalid) {}

    private static final DatePart[] PARTS = {
            new DatePart(SDTMM, 2, "12", "Start Date - Month can NOT be empty...", "Start Date - Not a valid Month..."),
            new DatePart(SDTDD, 2, "31", "Start Date - Day can NOT be empty...", "Start Date - Not a valid Day..."),
            new DatePart(SDTYYYY, 4, null, "Start Date - Year can NOT be empty...", null),
            new DatePart(EDTMM, 2, "12", "End Date - Month can NOT be empty...", "End Date - Not a valid Month..."),
            new DatePart(EDTDD, 2, "31", "End Date - Day can NOT be empty...", "End Date - Not a valid Day..."),
            new DatePart(EDTYYYY, 4, null, "End Date - Year can NOT be empty...", null) };

    enum Action implements ScreenAction {
        SUBMIT(Key.ENTER), BACK(Key.PF3);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /** @param user CDEMO-USER-ID, the submitted_by of the job */
    record State(UserId user) implements ScreenState {}

    private final JobSubmissions jobs;
    private final Clock clock;

    ReportSubmitScreen(JobSubmissions jobs, Clock clock) {
        super(REF, CORPT0A.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.jobs = jobs;
        this.clock = clock;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return send(SymbolicMap.lowValues(CORPT0A.MAP).cursor(MONTHLY), "", new State(arrival.signedOn().id()));
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<CORPT0A> map, State state) {
        return switch (action) {
            case SUBMIT -> processEnterKey(map, state);
            case BACK -> transfer(MainMenuScreen.REF.program());
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<CORPT0A> map, State state) {
        return send(map.cursor(MONTHLY), CommonMessages.INVALID_KEY, state);
    }

    private Outcome processEnterKey(SymbolicMap<CORPT0A> map, State state) {
        LocalDate today = LocalDate.now(clock);
        if (!map.isBlank(MONTHLY)) {
            return confirmAndSubmit(map, state, ReportDates.monthly(today));
        }
        if (!map.isBlank(YEARLY)) {
            return confirmAndSubmit(map, state, ReportDates.yearly(today));
        }
        if (!map.isBlank(CUSTOM)) {
            return custom(map, state);
        }
        return send(map.cursor(MONTHLY), "Select a report type to print report...", state);
    }

    private Outcome custom(SymbolicMap<CORPT0A> map, State state) {
        for (DatePart part : PARTS) {
            if (map.isBlank(part.field())) {
                return send(map.cursor(part.field()), part.empty(), state);
            }
        }
        for (DatePart part : PARTS) {
            map.set(part.field(), numericPart(map.text(part.field()), part.digits()));
        }
        for (DatePart part : PARTS) {
            if (part.limit() != null && map.text(part.field()).compareTo(part.limit()) > 0) {
                return send(map.cursor(part.field()), part.invalid(), state);
            }
        }
        String start = date(map, SDTYYYY, SDTMM, SDTDD);
        String end = date(map, EDTYYYY, EDTMM, EDTDD);
        if (unacceptable(DateCheck.check(start, PICTURE))) {
            return send(map.cursor(SDTMM), "Start Date - Not a valid date...", state);
        }
        if (unacceptable(DateCheck.check(end, PICTURE))) {
            return send(map.cursor(EDTMM), "End Date - Not a valid date...", state);
        }
        return submitCustom(map, state, start, end);
    }

    @LegacyDefect(id = "CORPT00C-non-numeric-date-part-is-zero",
            behavior = "NUMVAL-C of a date part with letters answers 0 under GnuCOBOL, so 20X6 becomes year 0000, "
                    + "which CSUTLDTC flags as message 2513 and the job is submitted",
            fix = "reject a date part that fails TEST-NUMVAL-C before converting it")
    private static String numericPart(String typed, int digits) {
        return Intrinsics.numvalCImage(typed, digits);
    }

    @LegacyDefect(id = "CORPT00C-pre-1582-accepted",
            goldens = {"report-submit-custom-pre-1582-accepted"},
            behavior = "CSUTLDTC message 2513, a date before 15 October 1582, is not treated as a date error",
            fix = "reject every severity other than 0000, including message 2513")
    private static boolean unacceptable(DateCheck.Feedback feedback) {
        return !feedback.valid() && feedback.msgNo() != DateCheck.UNSUPPORTED_RANGE;
    }

    @LegacyDefect(id = "CORPT00C-reversed-range-accepted",
            goldens = {"report-submit-custom-reversed-range"},
            behavior = "a custom end date before the start date is submitted; the two dates are never compared",
            fix = "reject the range when the end date is before the start date")
    private Outcome submitCustom(SymbolicMap<CORPT0A> map, State state, String start, String end) {
        return confirmAndSubmit(map, state, new Range("Custom", start, end));
    }

    private Outcome confirmAndSubmit(SymbolicMap<CORPT0A> map, State state, Range range) {
        String confirm = map.text(CONFIRM);
        if (confirm.isEmpty()) {
            return send(map.cursor(CONFIRM), "Please confirm to print the " + range.name() + " report...", state);
        }
        if (confirm.equals("N") || confirm.equals("n")) {
            return send(cleared(map), "", state);
        }
        if (!confirm.equals("Y") && !confirm.equals("y")) {
            return send(map.cursor(CONFIRM), "\"" + confirm + "\" is not a valid value to confirm...", state);
        }
        boolean wrote = jobs.submit(new SubmittedJob("JOBS", "TRNRPT00", "TRANREPT", range.start(), range.end(),
                ReportJcl.cards(range.start(), range.end()), state.user().value()));
        if (!wrote) {
            return send(map.cursor(MONTHLY), "Unable to Write TDQ (JOBS)...", state);
        }
        return send(cleared(map).color(ERRMSG, Color.GREEN), range.name() + " report submitted for printing ...", state);
    }

    private static String date(SymbolicMap<CORPT0A> map, CORPT0A year, CORPT0A month, CORPT0A day) {
        return map.text(year) + "-" + map.text(month) + "-" + map.text(day);
    }

    private static SymbolicMap<CORPT0A> cleared(SymbolicMap<CORPT0A> map) {
        return map.cursor(MONTHLY).blank(MONTHLY, YEARLY, CUSTOM, SDTMM, SDTDD, SDTYYYY, EDTMM, EDTDD, EDTYYYY, CONFIRM);
    }

    private Outcome send(SymbolicMap<CORPT0A> map, String message, State state) {
        return show(map.set(ERRMSG, message), state);
    }
}
