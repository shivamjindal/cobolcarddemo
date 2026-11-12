package com.carddemo.transactions;

import com.carddemo.batch.ReportSubmission;
import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CORPT00C. Asks TRANREPT for the current month, the current year or a typed date range. A confirmed request
 * goes to {@link ReportSubmission} where the legacy program wrote TRANREPT JCL to the internal reader.
 */
@Component
class ReportRequest implements ScreenProgram {

    private static final String MONTHLY = "MONTHLY";
    private static final String CONFIRM = "CONFIRM";

    private record Report(String name, LocalDate start, LocalDate end) {
    }

    /** One custom date field: its name in the messages and its PIC once NUMVAL-C has normalised it. */
    private record Part(String field, String name, int width, int max) {
    }

    private record Side(String label, Part month, Part day, Part year) {

        List<Part> parts() {
            return List.of(month, day, year);
        }
    }

    private static final Side START = new Side("Start Date", new Part("SDTMM", "Month", 2, 12),
            new Part("SDTDD", "Day", 2, 31), new Part("SDTYYYY", "Year", 4, 9999));
    private static final Side END = new Side("End Date", new Part("EDTMM", "Month", 2, 12),
            new Part("EDTDD", "Day", 2, 31), new Part("EDTYYYY", "Year", 4, 9999));
    private static final List<Side> SIDES = List.of(START, END);

    private final ReportSubmission submission;
    private final Clock clock;

    ReportRequest(ReportSubmission submission, Clock clock) {
        this.submission = submission;
        this.clock = clock;
    }

    @Override
    public Program program() {
        return Program.CORPT00C;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return new Outcome.Show(Map.of(), null, MONTHLY);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        return switch (aid) {
            case ENTER -> enter(fields);
            case PF3 -> new Outcome.Transfer(Program.COMEN01C);
            default -> new Outcome.Show(new LinkedHashMap<>(fields), Message.INVALID_KEY, MONTHLY);
        };
    }

    private Outcome enter(Map<String, String> fields) {
        LocalDate today = LocalDate.now(clock);
        if (marked(fields, MONTHLY)) {
            return confirm(fields, new Report("Monthly", today.withDayOfMonth(1),
                    today.with(TemporalAdjusters.lastDayOfMonth())));
        }
        if (marked(fields, "YEARLY")) {
            return confirm(fields, new Report("Yearly", today.withDayOfYear(1),
                    today.with(TemporalAdjusters.lastDayOfYear())));
        }
        if (marked(fields, "CUSTOM")) {
            return custom(new LinkedHashMap<>(fields));
        }
        return error(fields, "Select a report type to print report...", MONTHLY);
    }

    /** Every check runs over all six fields before the next check starts, so the first failure in that order wins. */
    private Outcome custom(Map<String, String> screen) {
        for (Side side : SIDES) {
            for (Part part : side.parts()) {
                if (text(screen, part.field).isBlank()) {
                    return error(screen, side.label + " - " + part.name + " can NOT be empty...", part.field);
                }
            }
        }
        SIDES.forEach(side -> side.parts().forEach(part -> number(screen, part)
                .ifPresent(n -> screen.put(part.field, ("%0" + part.width + "d").formatted(n)))));
        for (Side side : SIDES) {
            for (Part part : side.parts()) {
                if (number(screen, part).filter(n -> n <= part.max).isEmpty()) {
                    return error(screen, side.label + " - Not a valid " + part.name + "...", part.field);
                }
            }
        }
        Optional<LocalDate> start = calendar(screen, START);
        if (start.isEmpty()) {
            return error(screen, "Start Date - Not a valid date...", START.month.field);
        }
        Optional<LocalDate> end = calendar(screen, END);
        if (end.isEmpty()) {
            return error(screen, "End Date - Not a valid date...", END.month.field);
        }
        return confirm(screen, new Report("Custom", start.get(), end.get()));
    }

    private Outcome confirm(Map<String, String> screen, Report report) {
        String typed = text(screen, CONFIRM);
        return switch (typed) {
            case "" -> error(screen, "Please confirm to print the " + report.name + " report...", CONFIRM);
            case "Y", "y" -> {
                submission.submit(report.start, report.end);
                yield new Outcome.Show(Map.of(),
                        Message.success(report.name + " report submitted for printing ..."), MONTHLY);
            }
            case "N", "n" -> new Outcome.Show(Map.of(), null, MONTHLY);
            default -> error(screen, "\"" + typed + "\" is not a valid value to confirm...", CONFIRM);
        };
    }

    /**
     * NUMVAL-C of text that is not a number is undefined in Enterprise COBOL, so such a field fails its
     * not-a-valid check instead.
     */
    private static Optional<Integer> number(Map<String, String> screen, Part part) {
        String value = text(screen, part.field).strip();
        return value.matches("\\d{1," + part.width + "}") ? Optional.of(Integer.parseInt(value)) : Optional.empty();
    }

    /** CSUTLDTC. The legacy program ignored its feedback 2513 (before 1582-10-15), so any calendar day passes. */
    private static Optional<LocalDate> calendar(Map<String, String> screen, Side side) {
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(text(screen, side.year.field)),
                    Integer.parseInt(text(screen, side.month.field)), Integer.parseInt(text(screen, side.day.field))));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static boolean marked(Map<String, String> fields, String name) {
        return !text(fields, name).isBlank();
    }

    private static String text(Map<String, String> fields, String name) {
        return fields.getOrDefault(name, "");
    }

    private static Outcome error(Map<String, String> screen, String message, String cursor) {
        return new Outcome.Show(new LinkedHashMap<>(screen), Message.error(message), cursor);
    }
}
