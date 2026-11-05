package com.carddemo.batch.utilities;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;
import com.carddemo.records.KeyedTable;
import com.carddemo.vsam.KeyRange;

/**
 * IDCAMS for the statements REPROC, TRANBKP, COMBTRAN and CREASTMT code. Anything else ends RC 12.
 *
 * <p>REPRO into a KSDS without REPLACE counts duplicate keys and stops at ERRORLIMIT. z/OS DFSMS documents
 * that limit as 4: the command ends with condition code 12 once four records have been rejected, and a smaller
 * number of duplicates completes with condition code 8. DEFINE CLUSTER empties the named cluster and returns 0,
 * including when the cluster already exists, because these clusters are tables that stay in the schema.
 * DELETE ALTERNATEINDEX of a known alternate index returns 0 and leaves the SQL index in place.
 */
@Component
public final class Idcams implements BatchProgram {

    public static final String NAME = "IDCAMS";

    /** DFSMS REPRO ERRORLIMIT default. The fourth rejected record ends the command. */
    static final int ERROR_LIMIT = 4;

    private static final int FAILED = 12;
    private static final Set<String> VERBS = Set.of("REPRO", "DELETE", "DEFINE", "SET", "IF");
    private static final Pattern IF = Pattern.compile(
            "IF\\s+(LASTCC|MAXCC)\\s*(=|LE)\\s*(\\d+)\\s+THEN\\s+SET\\s+MAXCC\\s*=\\s*(\\d+)");
    private static final Pattern SET = Pattern.compile("SET\\s+MAXCC\\s*=\\s*(\\d+)");
    private static final Pattern DELETE = Pattern.compile("DELETE\\s+(\\S+)(?:\\s+(CLUSTER|ALTERNATEINDEX))?");
    private static final Pattern REPRO = Pattern.compile("REPRO\\s+(.*)", Pattern.DOTALL);
    private static final Pattern INFILE = Pattern.compile("INFILE\\s*\\(\\s*([A-Z0-9]+)\\s*\\)");
    private static final Pattern OUTFILE = Pattern.compile("OUTFILE\\s*\\(\\s*([A-Z0-9]+)\\s*\\)");
    private static final Pattern INDATASET = Pattern.compile("INDATASET\\s*\\(\\s*([^)]+?)\\s*\\)");
    private static final Pattern OUTDATASET = Pattern.compile("OUTDATASET\\s*\\(\\s*([^)]+?)\\s*\\)");
    private static final Pattern CLUSTER_NAME = Pattern.compile("NAME\\s*\\(\\s*([^)]+?)\\s*\\)");
    private static final Pattern KEYS = Pattern.compile("KEYS\\s*\\(\\s*(\\d+)\\s+(\\d+)\\s*\\)");
    private static final Pattern RECORDSIZE = Pattern.compile("RECORDSIZE\\s*\\(\\s*(\\d+)\\s+(\\d+)\\s*\\)");

    private final DatasetStore datasets;

    public Idcams(DatasetStore datasets) {
        this.datasets = datasets;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        List<String> messages = new ArrayList<>();
        int maxCc = 0;
        try {
            List<Command> commands = commands(cards(step, "SYSIN"));
            int lastCc = 0;
            for (Command command : commands) {
                if (command instanceof IfMaxcc reset) {
                    if (reset.holds(lastCc, maxCc)) {
                        maxCc = reset.value();
                    }
                    lastCc = 0;
                    continue;
                }
                lastCc = command.run(step, datasets, messages);
                if (command instanceof SetMaxcc set) {
                    maxCc = set.value();
                } else {
                    maxCc = Math.max(maxCc, lastCc);
                }
            }
            messages.add("IDC0002I IDCAMS PROCESSING COMPLETE. MAXIMUM CONDITION CODE WAS " + maxCc);
            return maxCc;
        } catch (ControlError e) {
            messages.add(e.getMessage());
            messages.add("IDC0002I IDCAMS PROCESSING COMPLETE. MAXIMUM CONDITION CODE WAS " + FAILED);
            return FAILED;
        } finally {
            spool(step, messages);
        }
    }

    private sealed interface Command {
        int run(Step step, DatasetStore datasets, List<String> messages);
    }

    private record Repro(Optional<String> infile, Optional<String> outfile, Optional<String> indataset,
            Optional<String> outdataset) implements Command {

        @Override
        public int run(Step step, DatasetStore datasets, List<String> messages) {
            List<byte[]> source = infile.map(dd -> readDd(step, dd)).orElseGet(
                    () -> datasets.readNamed(indataset.orElseThrow()).orElseThrow(
                            () -> new ControlError("IDC3012I ENTRY " + indataset.orElse("") + " NOT FOUND")));
            int rc = outfile.isPresent()
                    ? writeDd(step, datasets, outfile.get(), source, messages)
                    : writeDataset(datasets, outdataset.orElseThrow(), source, messages);
            messages.add("IDC0005I NUMBER OF RECORDS PROCESSED WAS " + source.size());
            return rc;
        }
    }

    private record Delete(String name, String kind) implements Command {
        @Override
        public int run(Step step, DatasetStore datasets, List<String> messages) {
            boolean deleted = switch (kind) {
                case "CLUSTER" -> deleteCluster(datasets, name);
                case "ALTERNATEINDEX" -> alternateIndex(name);
                case "" -> !KeyedTable.byDsn(name).isPresent() && datasets.deleteCataloged(name);
                default -> throw new ControlError("unsupported DELETE " + kind);
            };
            if (kind.isEmpty() && KeyedTable.byDsn(name).isPresent()) {
                throw new ControlError("DELETE of cluster " + name + " needs CLUSTER");
            }
            messages.add(deleted ? "IDC0550I ENTRY " + name + " DELETED" : "IDC3012I ENTRY " + name + " NOT FOUND");
            return deleted ? 0 : 8;
        }
    }

    private record DefineCluster(String name, int keyLength, int keyOffset, int lrecl) implements Command {
        @Override
        public int run(Step step, DatasetStore datasets, List<String> messages) {
            Optional<KeyedTable> table = KeyedTable.byDsn(name);
            if (table.isPresent()) {
                datasets.emptyCluster(table.get());
            } else {
                datasets.defineWorkCluster(name, lrecl, new KeyRange(keyOffset, keyLength));
            }
            messages.add("IDC0508I DATA ALLOCATION STATUS FOR VOLUME IS 0");
            messages.add("IDC0001I FUNCTION COMPLETED, HIGHEST CONDITION CODE WAS 0");
            return 0;
        }
    }

    private record SetMaxcc(int value) implements Command {
        @Override
        public int run(Step step, DatasetStore datasets, List<String> messages) {
            messages.add("IDC0001I FUNCTION COMPLETED, HIGHEST CONDITION CODE WAS " + value);
            return 0;
        }
    }

    private record IfMaxcc(String which, String op, int test, int value) implements Command {
        @Override
        public int run(Step step, DatasetStore datasets, List<String> messages) {
            return 0;
        }

        boolean holds(int lastCc, int maxCc) {
            int actual = which.equals("LASTCC") ? lastCc : maxCc;
            return op.equals("LE") ? actual <= test : actual == test;
        }
    }

    private static boolean deleteCluster(DatasetStore datasets, String name) {
        Optional<KeyedTable> table = KeyedTable.byDsn(name);
        if (table.isPresent()) {
            datasets.emptyCluster(table.get());
            return true;
        }
        return datasets.deleteCataloged(name);
    }

    private static boolean alternateIndex(String name) {
        return Arrays.stream(KeyedTable.values()).anyMatch(table -> table.alternatePath()
                .filter(path -> path.equals(name) || path.equals(name + ".PATH")).isPresent()
                || name.endsWith(".AIX") && table.dsn().equals(name.substring(0, name.length() - 4) + ".KSDS"));
    }

    private static int writeDd(Step step, DatasetStore datasets, String dd, List<byte[]> source, List<String> messages) {
        Optional<KeyedTable> table = tableOn(dd, step);
        if (table.isPresent()) {
            return loadTable(record -> datasets.insertCluster(table.get(), record), table.get().recordLength(), source,
                    messages);
        }
        Optional<KeyRange> key = step.key(dd);
        if (key.isPresent()) {
            return loadStored(step, dd, key.get(), source, messages);
        }
        List<byte[]> fitted = new ArrayList<>();
        int errors = 0;
        int rc = 0;
        int lrecl = step.lrecl(dd);
        for (int i = 0; i < source.size(); i++) {
            byte[] record = fit(source.get(i), lrecl);
            if (record == null) {
                errors++;
                messages.add("IDC3314I RECORD " + (i + 1) + " NOT WRITTEN, WRONG LENGTH");
                rc = errors >= ERROR_LIMIT ? FAILED : 8;
                if (errors >= ERROR_LIMIT) {
                    break;
                }
                continue;
            }
            fitted.add(record);
        }
        writeSequential(step, dd, fitted);
        return rc;
    }

    private static int writeDataset(DatasetStore datasets, String name, List<byte[]> source, List<String> messages) {
        Optional<KeyedTable> table = KeyedTable.byDsn(name);
        if (table.isPresent()) {
            return loadTable(record -> datasets.insertCluster(table.get(), record), table.get().recordLength(), source,
                    messages);
        }
        Optional<KeyRange> key = datasets.keyOf(name);
        if (key.isPresent()) {
            return loadNamed(datasets, name, key.get(), source, messages);
        }
        if (!datasets.replaceNamed(name, source)) {
            throw new ControlError("IDC3012I ENTRY " + name + " NOT FOUND");
        }
        return 0;
    }

    private static int loadNamed(DatasetStore datasets, String name, KeyRange key, List<byte[]> source,
            List<String> messages) {
        Map<Key, byte[]> records = new LinkedHashMap<>();
        for (byte[] existing : datasets.readNamed(name).orElse(List.of())) {
            records.put(new Key(Arrays.copyOfRange(existing, key.offset(), key.offset() + key.length())), existing);
        }
        int errors = 0;
        int rc = 0;
        int lrecl = datasets.lreclOf(name);
        for (int i = 0; i < source.size(); i++) {
            byte[] record = fit(source.get(i), lrecl);
            if (record == null || record.length < key.offset() + key.length()) {
                errors++;
                messages.add("IDC3314I RECORD " + (i + 1) + " NOT WRITTEN, WRONG LENGTH");
            } else {
                Key id = new Key(Arrays.copyOfRange(record, key.offset(), key.offset() + key.length()));
                if (records.containsKey(id)) {
                    errors++;
                    messages.add("IDC3314I RECORD " + (i + 1) + " NOT WRITTEN, DUPLICATE KEY");
                } else {
                    records.put(id, record);
                }
            }
            if (errors >= ERROR_LIMIT) {
                rc = FAILED;
                break;
            }
            rc = errors > 0 ? 8 : 0;
        }
        if (!datasets.replaceNamed(name, List.copyOf(records.values()))) {
            throw new ControlError("IDC3012I ENTRY " + name + " NOT FOUND");
        }
        return rc;
    }

    @FunctionalInterface
    private interface Insert {
        boolean insert(byte[] record);
    }

    private static int loadTable(Insert insert, int lrecl, List<byte[]> source, List<String> messages) {
        int errors = 0;
        int rc = 0;
        for (int i = 0; i < source.size(); i++) {
            byte[] record = fit(source.get(i), lrecl);
            if (record == null) {
                errors++;
                messages.add("IDC3314I RECORD " + (i + 1) + " NOT WRITTEN, WRONG LENGTH");
            } else if (!insert.insert(record)) {
                errors++;
                messages.add("IDC3314I RECORD " + (i + 1) + " NOT WRITTEN, DUPLICATE KEY");
            }
            if (errors >= ERROR_LIMIT) {
                return FAILED;
            }
            rc = errors > 0 ? 8 : 0;
        }
        return rc;
    }

    private static int loadStored(Step step, String dd, KeyRange key, List<byte[]> source, List<String> messages) {
        Map<Key, byte[]> records = new LinkedHashMap<>();
        for (byte[] existing : readDd(step, dd)) {
            records.put(new Key(Arrays.copyOfRange(existing, key.offset(), key.offset() + key.length())), existing);
        }
        int errors = 0;
        int rc = 0;
        int lrecl = step.lrecl(dd);
        for (int i = 0; i < source.size(); i++) {
            byte[] record = fit(source.get(i), lrecl == 0 ? recordLength(records) : lrecl);
            if (record == null || record.length < key.offset() + key.length()) {
                errors++;
                messages.add("IDC3314I RECORD " + (i + 1) + " NOT WRITTEN, WRONG LENGTH");
            } else {
                Key id = new Key(Arrays.copyOfRange(record, key.offset(), key.offset() + key.length()));
                if (records.containsKey(id)) {
                    errors++;
                    messages.add("IDC3314I RECORD " + (i + 1) + " NOT WRITTEN, DUPLICATE KEY");
                } else {
                    records.put(id, record);
                }
            }
            if (errors >= ERROR_LIMIT) {
                rc = FAILED;
                break;
            }
            rc = errors > 0 ? 8 : 0;
        }
        writeSequential(step, dd, List.copyOf(records.values()));
        return rc;
    }

    private static int recordLength(Map<Key, byte[]> records) {
        return records.values().stream().mapToInt(record -> record.length).findFirst().orElse(0);
    }

    private static Optional<KeyedTable> tableOn(String dd, Step step) {
        return step.dsn(dd).flatMap(KeyedTable::byDsn);
    }

    private static List<byte[]> readDd(Step step, String dd) {
        SequentialInput input = step.input(dd);
        FileStatus opened = input.open();
        if (opened != FileStatus.OK) {
            throw new ControlError(dd + " did not open, file status " + opened.code());
        }
        List<byte[]> records = new ArrayList<>();
        for (Read read = input.read(); read instanceof Read.Found found; read = input.read()) {
            records.add(found.record());
        }
        input.close();
        return records;
    }

    private static void writeSequential(Step step, String dd, List<byte[]> records) {
        SequentialOutput output = step.output(dd);
        FileStatus opened = output.open(OpenMode.OUTPUT);
        if (opened != FileStatus.OK) {
            throw new ControlError(dd + " did not open, file status " + opened.code());
        }
        for (byte[] record : records) {
            FileStatus written = output.write(record);
            if (written != FileStatus.OK) {
                throw new ControlError(dd + " rejected a " + record.length + " byte record, file status "
                        + written.code());
            }
        }
        output.close();
    }

    private static byte[] fit(byte[] record, int lrecl) {
        if (lrecl <= 0 || record.length == lrecl) {
            return record;
        }
        if (record.length > lrecl) {
            return null;
        }
        byte[] padded = new byte[lrecl];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(record, 0, padded, 0, record.length);
        return padded;
    }

    private static List<String> cards(Step step, String dd) {
        SequentialInput input = step.input(dd);
        if (input.open() != FileStatus.OK) {
            throw new ControlError("SYSIN did not open");
        }
        List<String> cards = new ArrayList<>();
        for (Read read = input.read(); read instanceof Read.Found found; read = input.read()) {
            cards.add(new String(found.record(), ISO_8859_1).stripTrailing());
        }
        input.close();
        return cards;
    }

    private static List<Command> commands(List<String> cards) {
        String text = join(cards).strip();
        if (text.isEmpty()) {
            throw new ControlError("SYSIN has no control statements");
        }
        List<Command> commands = new ArrayList<>();
        while (!text.isEmpty()) {
            text = text.stripLeading();
            if (text.startsWith("IF")) {
                Matcher matcher = IF.matcher(text);
                if (!matcher.lookingAt()) {
                    throw new ControlError("unsupported statement " + head(text));
                }
                commands.add(new IfMaxcc(matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4))));
                text = text.substring(matcher.end());
            } else if (text.startsWith("SET")) {
                Matcher matcher = SET.matcher(text);
                if (!matcher.lookingAt()) {
                    throw new ControlError("unsupported statement " + head(text));
                }
                commands.add(new SetMaxcc(Integer.parseInt(matcher.group(1))));
                text = text.substring(matcher.end());
            } else if (text.startsWith("DELETE")) {
                Matcher matcher = DELETE.matcher(text);
                if (!matcher.lookingAt()) {
                    throw new ControlError("unsupported statement " + head(text));
                }
                commands.add(new Delete(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2)));
                text = text.substring(matcher.end());
            } else if (text.startsWith("REPRO")) {
                Matcher matcher = REPRO.matcher(text);
                if (!matcher.lookingAt() || text.contains(" REPLACE")) {
                    throw new ControlError("unsupported statement " + head(text));
                }
                String body = matcher.group(1);
                int end = verbAfter(text, "REPRO".length());
                body = text.substring(0, end).substring("REPRO".length());
                commands.add(repro(body));
                text = text.substring(end);
            } else if (text.startsWith("DEFINE")) {
                int end = verbAfter(text, "DEFINE".length());
                commands.add(define(text.substring(0, end)));
                text = text.substring(end);
            } else {
                throw new ControlError("unsupported statement " + head(text));
            }
        }
        return commands;
    }

    private static Repro repro(String body) {
        Optional<String> infile = group(INFILE, body);
        Optional<String> indataset = group(INDATASET, body);
        Optional<String> outfile = group(OUTFILE, body);
        Optional<String> outdataset = group(OUTDATASET, body);
        if (infile.isPresent() == indataset.isPresent() || outfile.isPresent() == outdataset.isPresent()) {
            throw new ControlError("unsupported statement REPRO " + body.strip());
        }
        return new Repro(infile, outfile, indataset, outdataset);
    }

    private static DefineCluster define(String text) {
        if (!text.strip().matches("(?s)DEFINE\\s+CLUSTER\\b.*")) {
            throw new ControlError("unsupported statement " + head(text));
        }
        Matcher name = CLUSTER_NAME.matcher(text);
        Matcher keys = KEYS.matcher(text);
        Matcher record = RECORDSIZE.matcher(text);
        if (!name.find() || !keys.find() || !record.find()) {
            throw new ControlError("unsupported statement " + head(text));
        }
        return new DefineCluster(name.group(1), Integer.parseInt(keys.group(1)), Integer.parseInt(keys.group(2)),
                Integer.parseInt(record.group(2)));
    }

    private static Optional<String> group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String join(List<String> cards) {
        StringBuilder text = new StringBuilder();
        for (String card : cards) {
            String line = card.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.endsWith("-")) {
                text.append(line, 0, line.length() - 1).append(' ');
            } else {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    /** The index of the next command verb at parenthesis depth 0, or {@code text.length()}. */
    private static int verbAfter(String text, int from) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && i >= from && Character.isLetter(c)
                    && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
                int end = i;
                while (end < text.length() && Character.isLetter(text.charAt(end))) {
                    end++;
                }
                if (VERBS.contains(text.substring(i, end))) {
                    return i;
                }
            }
        }
        return text.length();
    }

    private static String head(String text) {
        String one = text.strip().lines().findFirst().orElse(text.strip());
        return one.length() > 72 ? one.substring(0, 72) : one;
    }

    private static void spool(Step step, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        SequentialOutput output = step.output("SYSPRINT");
        if (output.open(OpenMode.OUTPUT) != FileStatus.OK) {
            output = step.output("SYSOUT");
            if (output.open(OpenMode.OUTPUT) != FileStatus.OK) {
                lines.forEach(step::display);
                return;
            }
        }
        for (String line : lines) {
            byte[] bytes = line.getBytes(ISO_8859_1);
            if (output.write(bytes) != FileStatus.OK) {
                step.display(line);
            }
        }
        output.close();
    }

    private record Key(byte[] bytes) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private static final class ControlError extends RuntimeException {
        ControlError(String message) {
            super(message);
        }
    }
}
