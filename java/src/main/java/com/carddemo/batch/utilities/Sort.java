package com.carddemo.batch.utilities;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;
import com.carddemo.records.Fields;

/**
 * DFSORT for the control statements TRANREPT, COMBTRAN and CREASTMT actually code. Anything else ends RC 12.
 * Equal keys keep input order, which is one of the orders DFSORT may produce when EQUALS is not coded.
 */
@Component
public final class Sort implements BatchProgram {

    public static final String NAME = "SORT";

    private static final int FAILED = 12;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        List<String> messages = new ArrayList<>();
        try {
            Map<String, Symbol> symbols = symbols(cards(step, "SYMNAMES"));
            Request request = request(cards(step, "SYSIN"), symbols);
            List<byte[]> records = read(step, "SORTIN");
            if (request.include() != null) {
                records = records.stream().filter(record -> request.include().test(record)).toList();
            }
            records = new ArrayList<>(records);
            records.sort(request.keys());
            if (request.outrec() != null) {
                records = records.stream().map(record -> request.outrec().apply(record)).toList();
            }
            int lrecl = step.lrecl("SORTOUT");
            List<byte[]> fitted = new ArrayList<>(records.size());
            for (byte[] record : records) {
                fitted.add(fit(record, lrecl));
            }
            write(step, "SORTOUT", fitted);
            return 0;
        } catch (ControlError | NumberFormatException e) {
            messages.add(e instanceof ControlError ? e.getMessage() : "unsupported statement");
            return FAILED;
        } finally {
            spool(step, "SYSOUT", messages);
        }
    }

    private static List<String> cards(Step step, String dd) {
        SequentialInput input = step.input(dd);
        if (input.open() != FileStatus.OK) {
            return List.of();
        }
        List<String> cards = new ArrayList<>();
        for (Read read = input.read(); read instanceof Read.Found found; read = input.read()) {
            cards.add(new String(found.record(), ISO_8859_1).stripTrailing());
        }
        input.close();
        return cards;
    }

    private static List<byte[]> read(Step step, String dd) {
        SequentialInput input = step.input(dd);
        FileStatus opened = input.open();
        if (opened != FileStatus.OK) {
            throw new ControlError("SORTIN did not open, file status " + opened.code());
        }
        List<byte[]> records = new ArrayList<>();
        for (Read read = input.read(); read instanceof Read.Found found; read = input.read()) {
            records.add(found.record());
        }
        input.close();
        return records;
    }

    private static void write(Step step, String dd, List<byte[]> records) {
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
            return Arrays.copyOf(record, lrecl);
        }
        byte[] padded = new byte[lrecl];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(record, 0, padded, 0, record.length);
        return padded;
    }

    private static void spool(Step step, String dd, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        SequentialOutput output = step.output(dd);
        if (output.open(OpenMode.OUTPUT) != FileStatus.OK) {
            lines.forEach(step::display);
            return;
        }
        for (String line : lines) {
            byte[] bytes = line.getBytes(ISO_8859_1);
            if (output.write(bytes) != FileStatus.OK) {
                step.display(line);
            }
        }
        output.close();
    }

    private static Map<String, Symbol> symbols(List<String> cards) {
        Map<String, Symbol> symbols = new LinkedHashMap<>();
        for (String card : cards) {
            String text = card.strip();
            if (text.isEmpty()) {
                continue;
            }
            String definition = text.split("\\s+", 2)[0];
            String[] parts = definition.split(",", -1);
            try {
                if (parts.length == 4) {
                    symbols.put(parts[0], new Symbol.Field(Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]),
                            format(parts[3])));
                } else if (parts.length == 2 && parts[1].startsWith("C'") && parts[1].endsWith("'")) {
                    symbols.put(parts[0], new Symbol.Constant(parts[1].substring(2, parts[1].length() - 1)
                            .replace("''", "'").getBytes(ISO_8859_1)));
                } else {
                    throw new ControlError("unsupported SYMNAMES statement " + definition);
                }
            } catch (NumberFormatException e) {
                throw new ControlError("unsupported SYMNAMES statement " + definition);
            }
        }
        return symbols;
    }

    private static Request request(List<String> cards, Map<String, Symbol> symbols) {
        String joined = join(cards);
        if (joined.isBlank()) {
            throw new ControlError("SYSIN has no control statements");
        }
        List<Key> keys = null;
        Condition include = null;
        Outrec outrec = null;
        for (String statement : joined.strip().split("\\n")) {
            String text = statement.strip();
            if (text.isEmpty()) {
                continue;
            }
            String[] words = text.split("\\s+", 2);
            String rest = words.length > 1 ? words[1].strip() : "";
            switch (words[0]) {
                case "SORT" -> {
                    if (keys != null || !rest.startsWith("FIELDS=")) {
                        throw new ControlError("unsupported statement " + text);
                    }
                    keys = keys(inside(rest.substring("FIELDS=".length()), text), symbols);
                }
                case "INCLUDE" -> {
                    if (include != null || !rest.startsWith("COND=")) {
                        throw new ControlError("unsupported statement " + text);
                    }
                    include = include(inside(rest.substring("COND=".length()), text), symbols);
                }
                case "OUTREC" -> {
                    if (outrec != null || !rest.startsWith("FIELDS=")) {
                        throw new ControlError("unsupported statement " + text);
                    }
                    outrec = outrec(inside(rest.substring("FIELDS=".length()), text));
                }
                default -> throw new ControlError("unsupported statement " + text);
            }
        }
        if (keys == null) {
            throw new ControlError("SYSIN has no SORT statement");
        }
        return new Request(keys, include, outrec);
    }

    /** DFSORT continues a statement that ends in a comma or whose parentheses are still open. */
    private static String join(List<String> cards) {
        StringBuilder joined = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (String card : cards) {
            String text = card.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(text);
            if (current.toString().endsWith(",") || !balanced(current)) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append('\n');
            }
            joined.append(current);
            current.setLength(0);
        }
        if (!current.isEmpty()) {
            throw new ControlError("unsupported statement " + current);
        }
        return joined.toString();
    }

    private static boolean balanced(CharSequence text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return depth == 0;
    }

    private static String inside(String value, String statement) {
        String text = value.strip();
        if (!text.startsWith("(") || !text.endsWith(")")) {
            throw new ControlError("unsupported statement " + statement);
        }
        return text.substring(1, text.length() - 1);
    }

    private static List<Key> keys(String list, Map<String, Symbol> symbols) {
        List<String> tokens = tokens(list);
        List<Key> keys = new ArrayList<>();
        for (int i = 0; i < tokens.size();) {
            Symbol symbol = symbols.get(tokens.get(i));
            if (symbol instanceof Symbol.Field field) {
                keys.add(new Key(field.offset(), field.length(), field.format(), order(tokens, i + 1)));
                i += 2;
            } else if (symbol != null) {
                throw new ControlError("SORT FIELDS cannot name constant " + tokens.get(i));
            } else {
                if (i + 3 >= tokens.size()) {
                    throw new ControlError("unsupported SORT FIELDS (" + list + ")");
                }
                keys.add(new Key(Integer.parseInt(tokens.get(i)) - 1, Integer.parseInt(tokens.get(i + 1)),
                        format(tokens.get(i + 2)), order(tokens, i + 3)));
                i += 4;
            }
        }
        if (keys.isEmpty()) {
            throw new ControlError("unsupported SORT FIELDS (" + list + ")");
        }
        return keys;
    }

    private static Condition include(String list, Map<String, Symbol> symbols) {
        List<String> tokens = tokens(list);
        List<Comparison> comparisons = new ArrayList<>();
        for (int i = 0; i < tokens.size();) {
            Parsed left = field(tokens, i, symbols);
            String op = tokens.get(left.next());
            if (!op.equals("GE") && !op.equals("LE")) {
                throw new ControlError("unsupported INCLUDE operator " + op);
            }
            Parsed right = field(tokens, left.next() + 1, symbols);
            comparisons.add(new Comparison(left.operand(), op, right.operand()));
            i = right.next();
            if (i < tokens.size()) {
                if (!tokens.get(i).equals("AND")) {
                    throw new ControlError("unsupported INCLUDE connector " + tokens.get(i));
                }
                i++;
            }
        }
        if (comparisons.isEmpty()) {
            throw new ControlError("unsupported INCLUDE COND (" + list + ")");
        }
        return new Condition(comparisons);
    }

    private static Parsed field(List<String> tokens, int index, Map<String, Symbol> symbols) {
        if (index >= tokens.size()) {
            throw new ControlError("INCLUDE COND ends early");
        }
        Symbol symbol = symbols.get(tokens.get(index));
        if (symbol != null) {
            return new Parsed(new Operand.Named(symbol), index + 1);
        }
        if (index + 2 >= tokens.size()) {
            throw new ControlError("INCLUDE COND ends early");
        }
        return new Parsed(new Operand.Position(Integer.parseInt(tokens.get(index)) - 1,
                Integer.parseInt(tokens.get(index + 1)), format(tokens.get(index + 2))), index + 3);
    }

    private static Outrec outrec(String list) {
        List<String> tokens = tokens(list);
        List<Slice> slices = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i += 2) {
            String placed = tokens.get(i);
            int colon = placed.indexOf(':');
            if (colon <= 0 || i + 1 >= tokens.size()) {
                throw new ControlError("unsupported OUTREC FIELDS (" + list + ")");
            }
            slices.add(new Slice(Integer.parseInt(placed.substring(0, colon)) - 1,
                    Integer.parseInt(placed.substring(colon + 1)) - 1, Integer.parseInt(tokens.get(i + 1))));
        }
        if (slices.isEmpty()) {
            throw new ControlError("unsupported OUTREC FIELDS (" + list + ")");
        }
        return new Outrec(slices);
    }

    private static List<String> tokens(String list) {
        List<String> tokens = new ArrayList<>();
        for (String token : list.split(",")) {
            String text = token.strip();
            if (!text.isEmpty()) {
                tokens.add(text);
            }
        }
        return tokens;
    }

    private static String order(List<String> tokens, int index) {
        if (index >= tokens.size()) {
            throw new ControlError("SORT FIELDS is missing an order");
        }
        String order = tokens.get(index);
        if (!order.equals("A") && !order.equals("D")) {
            throw new ControlError("unsupported SORT order " + order);
        }
        return order;
    }

    private static Format format(String format) {
        try {
            return Format.valueOf(format);
        } catch (IllegalArgumentException e) {
            throw new ControlError("unsupported format " + format);
        }
    }

    private enum Format {
        CH, ZD
    }

    private sealed interface Symbol {
        record Field(int offset, int length, Format format) implements Symbol {}

        record Constant(byte[] bytes) implements Symbol {}
    }

    private sealed interface Operand {
        record Named(Symbol symbol) implements Operand {}

        record Position(int offset, int length, Format format) implements Operand {}
    }

    private record Parsed(Operand operand, int next) {}

    private record Key(int offset, int length, Format format, String order) implements java.util.Comparator<byte[]> {

        @Override
        public int compare(byte[] left, byte[] right) {
            int compared = compareField(left, offset, right, offset, length, format);
            return order.equals("D") ? -compared : compared;
        }

        static java.util.Comparator<byte[]> of(List<Key> keys) {
            java.util.Comparator<byte[]> comparator = keys.getFirst();
            for (Key key : keys.subList(1, keys.size())) {
                comparator = comparator.thenComparing(key);
            }
            return comparator;
        }
    }

    private record Comparison(Operand left, String op, Operand right) {

        boolean test(byte[] record) {
            Resolved l = resolve(record, left);
            Resolved r = resolve(record, right);
            Format format = l.format() != null ? l.format() : r.format();
            int compared = compareBytes(l.bytes(), r.bytes(), format == null ? Format.CH : format);
            return op.equals("GE") ? compared >= 0 : compared <= 0;
        }
    }

    private record Condition(List<Comparison> comparisons) {
        boolean test(byte[] record) {
            return comparisons.stream().allMatch(comparison -> comparison.test(record));
        }
    }

    private record Slice(int column, int offset, int length) {}

    private record Outrec(List<Slice> slices) {
        byte[] apply(byte[] record) {
            int end = 0;
            for (Slice slice : slices) {
                end = Math.max(end, slice.column() + slice.length());
                if (slice.offset() < 0 || slice.length() < 0 || slice.offset() + slice.length() > record.length) {
                    throw new ControlError("OUTREC field " + (slice.offset() + 1) + "," + slice.length()
                            + " is outside a " + record.length + " byte record");
                }
            }
            byte[] out = new byte[end];
            Arrays.fill(out, (byte) ' ');
            for (Slice slice : slices) {
                System.arraycopy(record, slice.offset(), out, slice.column(), slice.length());
            }
            return out;
        }
    }

    private record Request(List<Key> keyList, Condition include, Outrec outrec) {
        java.util.Comparator<byte[]> keys() {
            return Key.of(keyList);
        }
    }

    private record Resolved(byte[] bytes, Format format) {}

    private static Resolved resolve(byte[] record, Operand operand) {
        return switch (operand) {
            case Operand.Named named -> switch (named.symbol()) {
                case Symbol.Field field -> new Resolved(slice(record, field.offset(), field.length()), field.format());
                case Symbol.Constant constant -> new Resolved(constant.bytes(), null);
            };
            case Operand.Position position ->
                    new Resolved(slice(record, position.offset(), position.length()), position.format());
        };
    }

    private static byte[] slice(byte[] record, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > record.length) {
            throw new ControlError("field " + (offset + 1) + "," + length + " is outside a " + record.length
                    + " byte record");
        }
        return Arrays.copyOfRange(record, offset, offset + length);
    }

    private static int compareField(byte[] left, int leftAt, byte[] right, int rightAt, int length, Format format) {
        if (leftAt + length > left.length || rightAt + length > right.length) {
            throw new ControlError("sort field " + (leftAt + 1) + "," + length + " is outside the record");
        }
        return compareBytes(Arrays.copyOfRange(left, leftAt, leftAt + length),
                Arrays.copyOfRange(right, rightAt, rightAt + length), format);
    }

    private static int compareBytes(byte[] left, byte[] right, Format format) {
        if (format == Format.ZD && Fields.isZoned(left, 0, left.length, true)
                && Fields.isZoned(right, 0, right.length, true)) {
            return Fields.zoned(left, 0, left.length, 0).compareTo(Fields.zoned(right, 0, right.length, 0));
        }
        int length = Math.max(left.length, right.length);
        return Arrays.compareUnsigned(padded(left, length), padded(right, length));
    }

    private static byte[] padded(byte[] bytes, int length) {
        if (bytes.length == length) {
            return bytes;
        }
        byte[] padded = new byte[length];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(bytes, 0, padded, 0, Math.min(bytes.length, length));
        return padded;
    }

    private static final class ControlError extends RuntimeException {
        ControlError(String message) {
            super(message);
        }
    }
}
