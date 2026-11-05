package com.carddemo.batch.jcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JCL the internal reader accepts: what CORPT00C submits and what the characterization adapter writes. JOB
 * (continued over cards), comments, JCLLIB, {@code EXEC PGM=} with PARM and COND, {@code EXEC PROC=} with
 * {@code procstep.dd} overrides, DD with DSN, DISP, DCB, SYSOUT, DUMMY or instream data, concatenation, and the
 * {@code /*} and {@code /*EOF} delimiters. A {@code DD *} also ends at the next {@code //} statement. Instream
 * cards are stored without trailing blanks; a read pads each one to 80. UNIT, SPACE and BLKSIZE are ignored.
 * STEPLIB and JOBLIB DDs are ignored: programs come from the program library. An override DD replaces the
 * procedure's DD whole rather than merging parameters.
 */
public final class JclDeck {

    private static final Pattern GENERATION = Pattern.compile("(.+)\\(([+-]?\\d+)\\)");
    private static final List<String> LOAD_LIBRARIES = List.of("STEPLIB", "JOBLIB");

    public sealed interface Parsed {

        record Job(JobDef job) implements Parsed {}

        record Error(String reason) implements Parsed {}
    }

    private record Statement(String name, String operation, String operands, List<String> data) {}

    private static final class JclError extends RuntimeException {
        JclError(String message) {
            super(message);
        }
    }

    /** A step being built: its DDs stay editable until the next EXEC. */
    private record Building(String path, String program, String parm, Cond cond, List<Dd> dds) {

        StepDef step() {
            return new StepDef(path, program, parm, cond, dds);
        }

        String procStep() {
            return path.substring(path.indexOf('.') + 1);
        }
    }

    private JclDeck() {}

    public static Parsed parse(List<String> cards, Function<String, Optional<ProcDef>> procs) {
        try {
            return new Parsed.Job(job(statements(cards), procs));
        } catch (JclError e) {
            return new Parsed.Error(e.getMessage());
        }
    }

    private static List<Statement> statements(List<String> cards) {
        List<Statement> statements = new ArrayList<>();
        int i = 0;
        while (i < cards.size()) {
            String card = cards.get(i);
            if (card.startsWith("/*EOF") || card.startsWith("//") && card.substring(2).isBlank()) {
                break;
            }
            if (card.startsWith("//*") || card.startsWith("/*")) {
                i++;
                continue;
            }
            if (!card.startsWith("//")) {
                throw new JclError("data card outside DD *: " + card);
            }
            String text = card.substring(2);
            int nameEnd = text.indexOf(' ');
            String name = nameEnd < 0 ? text : text.substring(0, nameEnd);
            String rest = nameEnd < 0 ? "" : text.substring(nameEnd).stripLeading();
            int operationEnd = rest.indexOf(' ');
            String operation = operationEnd < 0 ? rest : rest.substring(0, operationEnd);
            StringBuilder operands = new StringBuilder(field(operationEnd < 0 ? "" : rest.substring(operationEnd).stripLeading()));
            while (operands.toString().endsWith(",") && i + 1 < cards.size() && cards.get(i + 1).startsWith("// ")) {
                i++;
                operands.append(field(cards.get(i).substring(2).stripLeading()));
            }
            i++;
            List<String> data = new ArrayList<>();
            if (operation.equals("DD") && (operands.toString().equals("*") || operands.toString().equals("DATA")
                    || operands.toString().startsWith("*,") || operands.toString().startsWith("DATA,"))) {
                while (i < cards.size() && !cards.get(i).startsWith("//") && !cards.get(i).startsWith("/*")) {
                    data.add(cards.get(i++).stripTrailing());
                }
                if (i < cards.size() && cards.get(i).startsWith("/*") && !cards.get(i).startsWith("/*EOF")) {
                    i++;
                }
            }
            statements.add(new Statement(name, operation, operands.toString(), data));
        }
        return statements;
    }

    /** The operand field: up to the first blank outside quotes; the rest of the card is comment. */
    private static String field(String text) {
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (c == ' ' && !quoted) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    private static JobDef job(List<Statement> statements, Function<String, Optional<ProcDef>> procs) {
        if (statements.isEmpty() || !statements.getFirst().operation().equals("JOB")
                || statements.getFirst().name().isEmpty()) {
            throw new JclError("the deck does not start with a named JOB statement");
        }
        String jobName = statements.getFirst().name();
        List<Building> steps = new ArrayList<>();
        List<Building> procSteps = List.of();
        boolean skipping = false;
        for (Statement statement : statements.subList(1, statements.size())) {
            switch (statement.operation()) {
                case "JCLLIB" -> skipping = false;
                case "EXEC" -> {
                    skipping = false;
                    procSteps = exec(statement, procs);
                    steps.addAll(procSteps);
                }
                case "DD" -> {
                    String name = statement.name();
                    skipping = LOAD_LIBRARIES.contains(name) || name.isEmpty() && skipping;
                    if (skipping) {
                        continue;
                    }
                    if (steps.isEmpty()) {
                        throw new JclError("DD " + name + " before the first EXEC");
                    }
                    Building step = target(name, steps.getLast(), procSteps);
                    DdSource source = source(statement, step);
                    String ddName = name.substring(name.indexOf('.') + 1);
                    if (name.isEmpty()) {
                        if (step.dds().isEmpty()) {
                            throw new JclError("unnamed DD with nothing to concatenate to");
                        }
                        Dd last = step.dds().removeLast();
                        List<DdSource> concatenation = new ArrayList<>(last.concatenation());
                        concatenation.add(source);
                        step.dds().add(new Dd(last.name(), concatenation));
                    } else {
                        replace(step.dds(), Dd.of(ddName, source));
                    }
                }
                default -> throw new JclError("unsupported statement " + statement.operation());
            }
        }
        if (steps.isEmpty()) {
            throw new JclError("job " + jobName + " has no steps");
        }
        return new JobDef(jobName, steps.stream().map(Building::step).toList());
    }

    private static List<Building> exec(Statement statement, Function<String, Optional<ProcDef>> procs) {
        if (statement.name().isEmpty()) {
            throw new JclError("EXEC without a step name");
        }
        Map<String, String> keywords = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        operands(statement.operands(), keywords, positional);
        Optional<Cond> cond = Optional.ofNullable(keywords.get("COND")).map(JclDeck::cond);
        if (keywords.containsKey("PGM")) {
            String parm = Optional.ofNullable(keywords.get("PARM")).map(JclDeck::parm).orElse("");
            return List.of(new Building(statement.name(), keywords.get("PGM"), parm, cond.orElse(Cond.NONE),
                    new ArrayList<>()));
        }
        String member = keywords.containsKey("PROC") ? keywords.get("PROC")
                : positional.isEmpty() ? null : positional.getFirst();
        if (member == null) {
            throw new JclError("EXEC " + statement.name() + " names no program or procedure");
        }
        ProcDef proc = procs.apply(member).orElseThrow(() -> new JclError("procedure " + member + " not found"));
        return proc.steps().stream()
                .map(s -> new Building(statement.name() + "." + s.path(), s.program(), s.parm(), cond.orElse(s.cond()),
                        new ArrayList<>(s.dds())))
                .toList();
    }

    /**
     * An override names {@code procstep.dd}. An unqualified DD after {@code EXEC PROC} is added to the first
     * procedure step, as JCL does; after {@code EXEC PGM} it belongs to that step.
     */
    private static Building target(String name, Building last, List<Building> procSteps) {
        int dot = name.indexOf('.');
        if (dot < 0) {
            return name.isEmpty() || !procSteps.getFirst().path().contains(".") ? last : procSteps.getFirst();
        }
        String procStep = name.substring(0, dot);
        return procSteps.stream()
                .filter(s -> s.path().contains(".") && s.procStep().equals(procStep))
                .findFirst()
                .orElseThrow(() -> new JclError("override " + name + " names no step of the procedure"));
    }

    private static void replace(List<Dd> dds, Dd dd) {
        for (int i = 0; i < dds.size(); i++) {
            if (dds.get(i).name().equals(dd.name())) {
                dds.set(i, dd);
                return;
            }
        }
        dds.add(dd);
    }

    private static DdSource source(Statement statement, Building step) {
        Map<String, String> keywords = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        operands(statement.operands(), keywords, positional);
        if (positional.contains("*") || positional.contains("DATA")) {
            return new DdSource.Instream(statement.data());
        }
        if (positional.contains("DUMMY")) {
            return new DdSource.Dummy();
        }
        Optional<Dcb> dcb = dcb(keywords, step);
        if (keywords.containsKey("SYSOUT")) {
            return new DdSource.Sysout(dcb);
        }
        String dsn = keywords.containsKey("DSN") ? keywords.get("DSN") : keywords.get("DSNAME");
        if (dsn == null) {
            throw new JclError("DD " + statement.name() + " names no data set");
        }
        return new DdSource.Dataset(dsnRef(dsn), disposition(keywords.get("DISP")), dcb);
    }

    private static DsnRef dsnRef(String dsn) {
        Matcher generation = GENERATION.matcher(dsn);
        if (!generation.matches()) {
            return new DsnRef.Named(dsn);
        }
        Gdg gdg = Gdg.byBase(generation.group(1))
                .orElseThrow(() -> new JclError(generation.group(1) + " is not a generation data group"));
        return new DsnRef.Generation(gdg, Integer.parseInt(generation.group(2)));
    }

    private static Disposition disposition(String value) {
        if (value == null) {
            return Disposition.of(null, null, null);
        }
        List<String> parts = list(value);
        try {
            return Disposition.of(
                    part(parts, 0).map(Disposition.Status::valueOf).orElse(null),
                    part(parts, 1).map(Disposition.End::valueOf).orElse(null),
                    part(parts, 2).map(Disposition.End::valueOf).orElse(null));
        } catch (IllegalArgumentException e) {
            throw new JclError("DISP=" + value + " is not supported");
        }
    }

    private static Optional<Dcb> dcb(Map<String, String> keywords, Building step) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (keywords.containsKey("DCB")) {
            List<String> parts = list(keywords.get("DCB"));
            if (parts.size() == 1 && parts.getFirst().startsWith("*.")) {
                String referenced = parts.getFirst().substring(2);
                return step.dds().stream()
                        .filter(dd -> dd.name().equals(referenced))
                        .findFirst()
                        .flatMap(dd -> switch (dd.source()) {
                            case DdSource.Dataset dataset -> dataset.dcb();
                            case DdSource.Sysout sysout -> sysout.dcb();
                            default -> Optional.empty();
                        });
            }
            operands(String.join(",", parts), attributes, new ArrayList<>());
        }
        keywords.forEach((key, value) -> {
            if (key.equals("RECFM") || key.equals("LRECL")) {
                attributes.put(key, value);
            }
        });
        if (!attributes.containsKey("RECFM") && !attributes.containsKey("LRECL")) {
            return Optional.empty();
        }
        String recfm = attributes.getOrDefault("RECFM", "F");
        Dcb.Recfm format = switch (recfm.charAt(0)) {
            case 'F' -> Dcb.Recfm.F;
            case 'V' -> Dcb.Recfm.V;
            default -> throw new JclError("RECFM=" + recfm + " is not supported");
        };
        try {
            return Optional.of(new Dcb(format, Integer.parseInt(attributes.getOrDefault("LRECL", "0"))));
        } catch (NumberFormatException e) {
            throw new JclError("LRECL=" + attributes.get("LRECL") + " is not a number");
        }
    }

    /** {@code (code,op[,step])} or a list of them. EVEN and ONLY are not supported. */
    private static Cond cond(String value) {
        List<String> parts = list(value);
        List<String> tests = parts.getFirst().startsWith("(") ? parts : List.of(value);
        List<Cond.Test> parsed = new ArrayList<>();
        for (String test : tests) {
            List<String> fields = list(test);
            try {
                parsed.add(new Cond.Test(Integer.parseInt(fields.get(0)), Cond.Operator.valueOf(fields.get(1)),
                        part(fields, 2)));
            } catch (RuntimeException e) {
                throw new JclError("COND=" + value + " is not supported");
            }
        }
        return new Cond(parsed);
    }

    private static String parm(String value) {
        if (value.startsWith("'")) {
            return unquote(value);
        }
        return value.startsWith("(") ? value.substring(1, value.length() - 1) : value;
    }

    private static void operands(String text, Map<String, String> keywords, List<String> positional) {
        for (String operand : split(text)) {
            int equals = keywordEnd(operand);
            if (equals > 0) {
                keywords.put(operand.substring(0, equals), operand.substring(equals + 1));
            } else {
                positional.add(operand);
            }
        }
    }

    private static int keywordEnd(String operand) {
        int equals = operand.indexOf('=');
        return equals > 0 && operand.substring(0, equals).matches("[A-Z][A-Z0-9.]*") ? equals : -1;
    }

    /** The items of a parenthesized list, or the value alone. */
    private static List<String> list(String value) {
        return value.startsWith("(") && value.endsWith(")") ? split(value.substring(1, value.length() - 1)) : List.of(value);
    }

    private static List<String> split(String text) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                items.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (quoted || depth != 0) {
            throw new JclError("unbalanced operands: " + text);
        }
        if (!text.isEmpty()) {
            items.add(text.substring(start));
        }
        return items;
    }

    private static Optional<String> part(List<String> parts, int index) {
        return index < parts.size() && !parts.get(index).isEmpty() ? Optional.of(parts.get(index)) : Optional.empty();
    }

    private static String unquote(String value) {
        return value.substring(1, value.length() - 1).replace("''", "'");
    }
}
