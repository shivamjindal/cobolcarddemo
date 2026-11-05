package com.carddemo.characterization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.carddemo.records.Fields;
import com.carddemo.vsam.CopybookParser;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.Layout;
import com.carddemo.vsam.Pic;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A registry dataset's copybook as {@code characterization/harness/copybook.py} uses it: named field values
 * moved into a record ({@code Layout.encode}), and a record read back as the values the harness compares.
 */
final class HarnessLayout {

    private static final Pattern LEVEL_01 = Pattern.compile("(?m)^.{6} +0?1 +[A-Z]");

    private final String name;
    private final int recordLength;
    private final List<Field> fields;

    private HarnessLayout(String name, int recordLength, List<Field> fields) {
        this.name = name;
        this.recordLength = recordLength;
        this.fields = fields;
    }

    static HarnessLayout of(Path repo, JsonNode registry, String name) throws IOException {
        JsonNode entry = registry.path("datasets").path(name);
        return ofCopybook(repo, entry.get("copybook").asText(), entry.get("reclen").asInt(), name);
    }

    /** A copybook no dataset describes, as a batch output's {@code copybook} names it. */
    static HarnessLayout ofCopybook(Path repo, String copybook, int recordLength) throws IOException {
        return ofCopybook(repo, copybook, recordLength, copybook);
    }

    private static HarnessLayout ofCopybook(Path repo, String copybook, int recordLength, String name) throws IOException {
        String text = Files.readString(repo.resolve(copybook), ISO_8859_1);
        if (!LEVEL_01.matcher(text).find()) {
            // A segment copybook of bare 05 items (CIPAUDTY) is one record, as the harness reads it.
            text = "       01  RECORD.\n" + text;
        }
        Layout layout = new CopybookParser().parse(text);
        List<Field> fields = new ArrayList<>(layout.fields());
        fields.addAll(layout.fillers());
        fields.sort(Comparator.comparingInt(Field::offset));
        return new HarnessLayout(name, recordLength, List.copyOf(fields));
    }

    /** A layout the copybook parser cannot read, such as a segment's with OCCURS, from fields in offset order. */
    static HarnessLayout of(String name, int recordLength, List<Field> fields) {
        return new HarnessLayout(name, recordLength, List.copyOf(fields));
    }

    int recordLength() {
        return recordLength;
    }

    /** Each named field's value moved into a copy of {@code base}, or of spaces. */
    byte[] encode(JsonNode values, byte[] base) {
        byte[] record = base == null ? Fields.spaces(recordLength) : base.clone();
        values.fields().forEachRemaining(entry -> {
            Field field = fields.stream()
                    .filter(f -> f.cobolName().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(name + " has no field " + entry.getKey()));
            String value = entry.getValue().asText();
            switch (field.pic()) {
                case Pic.Zoned zoned -> Fields.zoned(record, field.offset(), field.length(), zoned.scale(),
                        zoned.signed(), new BigDecimal(value));
                case Pic.Packed packed -> encodePacked(record, field, packed.scale(), packed.signed(), value);
                case Pic.Binary binary -> encodeBinary(record, field, binary.scale(), binary.signed(), value);
                case Pic.Alnum alnum -> Fields.text(record, field.offset(), field.length(), value);
            }
        });
        return record;
    }

    /** {@code Datasets.decode}: every field but FILLER, zoned digits as a scaled decimal, then rstripped. */
    Map<String, String> decode(byte[] record) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : fields) {
            if (!field.cobolName().contains("FILLER")) {
                values.put(field.cobolName(), Python.rstrip(value(record, field)));
            }
        }
        return values;
    }

    /**
     * {@code copybook.decode_leaf}: characters, unless the field is zoned and holds zoned digits. Python's
     * Decimal keeps the sign of a negative zero, so {@code 000}} reads {@code -0}.
     */
    private static String value(byte[] record, Field field) {
        if (field.pic() instanceof Pic.Packed packed) {
            return decodePacked(record, field, packed.scale());
        }
        if (field.pic() instanceof Pic.Binary binary) {
            byte[] bytes = Arrays.copyOfRange(record, field.offset(), field.offset() + field.length());
            BigInteger value = binary.signed() ? new BigInteger(bytes) : new BigInteger(1, bytes);
            return new BigDecimal(value, binary.scale()).toPlainString();
        }
        String raw = Fields.text(record, field.offset(), field.length());
        if (field.pic() instanceof Pic.Zoned zoned && !raw.replaceAll("[ \0]", "").isEmpty()
                && Fields.isZoned(record, field.offset(), field.length(), true)) {
            BigDecimal value = Fields.zoned(record, field.offset(), field.length(), zoned.scale());
            boolean negativeZero = value.signum() == 0 && raw.endsWith("}");
            return (negativeZero ? "-" : "") + value.toPlainString();
        }
        return raw;
    }

    /** The key a diff reports: the decoded {@code keyFields} joined with "/". */
    String keyText(byte[] record, List<String> keyFields) {
        Map<String, String> values = decode(record);
        return String.join("/", keyFields.stream().map(f -> Python.rstrip(values.get(f))).toList());
    }

    /** Python's {@code Decimal}: the sign nibble B or D is negative, and a negative zero keeps its sign. */
    private static String decodePacked(byte[] record, Field field, int scale) {
        String hex = HexFormat.of().formatHex(record, field.offset(), field.offset() + field.length());
        char sign = hex.charAt(hex.length() - 1);
        String digits = hex.substring(0, hex.length() - 1);
        if (!digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(field.cobolName() + " holds x'" + hex + "', not packed digits");
        }
        boolean negative = sign == 'b' || sign == 'd';
        BigDecimal value = new BigDecimal(new BigInteger(digits.isEmpty() ? "0" : digits), scale);
        return (negative && value.signum() == 0 ? "-" : "") + (negative ? value.negate() : value).toPlainString();
    }

    private static void encodePacked(byte[] record, Field field, int scale, boolean signed, String value) {
        BigInteger units = new BigDecimal(value).movePointRight(scale).setScale(0, RoundingMode.HALF_EVEN)
                .toBigIntegerExact();
        String digits = "0".repeat(Math.max(0, field.length() * 2 - 1 - units.abs().toString().length()))
                + units.abs();
        String sign = units.signum() < 0 ? "d" : signed ? "c" : "f";
        byte[] packed = HexFormat.of().parseHex(digits + sign);
        if (packed.length != field.length()) {
            throw new IllegalArgumentException(field.cobolName() + " cannot hold " + value);
        }
        System.arraycopy(packed, 0, record, field.offset(), packed.length);
    }

    private static void encodeBinary(byte[] record, Field field, int scale, boolean signed, String value) {
        BigInteger units = new BigDecimal(value).movePointRight(scale).setScale(0, RoundingMode.HALF_EVEN)
                .toBigIntegerExact();
        if (!signed && units.signum() < 0 || units.bitLength() > field.length() * 8 - (signed ? 1 : 0)) {
            throw new IllegalArgumentException(field.cobolName() + " cannot hold " + value);
        }
        byte[] bytes = units.toByteArray();
        byte fill = (byte) (units.signum() < 0 ? 0xFF : 0);
        for (int i = 0; i < field.length(); i++) {
            int from = bytes.length - field.length() + i;
            record[field.offset() + i] = from < 0 ? fill : bytes[from];
        }
    }
}
