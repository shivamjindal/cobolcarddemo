package com.carddemo.characterization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Layout layout = new CopybookParser().parse(
                Files.readString(repo.resolve(entry.get("copybook").asText()), ISO_8859_1));
        List<Field> fields = new ArrayList<>(layout.fields());
        fields.addAll(layout.fillers());
        fields.sort(Comparator.comparingInt(Field::offset));
        return new HarnessLayout(name, entry.get("reclen").asInt(), List.copyOf(fields));
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
            if (field.pic() instanceof Pic.Zoned zoned) {
                Fields.zoned(record, field.offset(), field.length(), zoned.scale(), zoned.signed(), new BigDecimal(value));
            } else {
                Fields.text(record, field.offset(), field.length(), value);
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
        String raw = Fields.text(record, field.offset(), field.length());
        if (field.pic() instanceof Pic.Zoned zoned && !raw.replaceAll("[ \0]", "").isEmpty()
                && Fields.isZoned(record, field.offset(), field.length(), true)) {
            BigDecimal value = Fields.zoned(record, field.offset(), field.length(), zoned.scale());
            boolean negativeZero = value.signum() == 0 && raw.endsWith("}");
            return (negativeZero ? "-" : "") + value.toPlainString();
        }
        return raw;
    }
}
