package com.carddemo.online;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

/**
 * The BMS symbolic map of one task: what the program moved into {@code xxxO}, {@code xxxL},
 * {@code xxxA} and {@code xxxC}. The I and O areas share storage, so a received map starts with the
 * transmitted values.
 *
 * <p>A value is {@code null} for LOW-VALUES, {@code ""} for SPACES, or rstripped text no longer than
 * the field. Cursor requests accumulate like {@code MOVE -1 TO xL}, and the earliest field in map
 * order wins. The last color or attribute moved to a field wins. One task owns it; it is never stored.
 */
public final class SymbolicMap<F extends Enum<F> & MapField> {

    private final MapId<F> map;
    private final EnumMap<F, String> values;
    private final EnumMap<F, Color> colors;
    private final EnumMap<F, FieldAttribute> attributes;
    private final EnumSet<F> cursorRequests;

    private SymbolicMap(MapId<F> map) {
        this.map = map;
        this.values = new EnumMap<>(map.type());
        this.colors = new EnumMap<>(map.type());
        this.attributes = new EnumMap<>(map.type());
        this.cursorRequests = EnumSet.noneOf(map.type());
    }

    /** {@code MOVE LOW-VALUES TO xxxO}. */
    public static <F extends Enum<F> & MapField> SymbolicMap<F> lowValues(MapId<F> map) {
        return new SymbolicMap<>(map);
    }

    /** RECEIVE MAP into fresh storage: transmitted fields hold their data, the rest LOW-VALUES. */
    static <F extends Enum<F> & MapField> SymbolicMap<F> received(MapId<F> map, Map<F, String> transmitted) {
        SymbolicMap<F> received = new SymbolicMap<>(map);
        transmitted.forEach((field, value) -> received.values.put(field, value.isEmpty() ? null : value.stripTrailing()));
        return received;
    }

    /** {@code MOVE value TO fO}: truncated to the field, trailing blanks dropped. */
    public SymbolicMap<F> set(F field, String value) {
        String moved = value.length() > field.length() ? value.substring(0, field.length()) : value;
        values.put(field, moved.stripTrailing());
        return this;
    }

    /** {@code MOVE SPACES TO fO}. */
    @SafeVarargs
    public final SymbolicMap<F> blank(F... fields) {
        for (F field : fields) {
            values.put(field, "");
        }
        return this;
    }

    /** {@code MOVE LOW-VALUES TO fO}. */
    @SafeVarargs
    public final SymbolicMap<F> lowValue(F... fields) {
        for (F field : fields) {
            values.remove(field);
        }
        return this;
    }

    /** The field's characters with trailing blanks dropped; LOW-VALUES read as "". */
    public String text(F field) {
        String value = values.get(field);
        return value == null ? "" : value;
    }

    /** {@code f = SPACES OR LOW-VALUES}. */
    public boolean isBlank(F field) {
        return text(field).isEmpty();
    }

    /** {@code f = LOW-VALUES}. */
    public boolean isLowValues(F field) {
        return values.get(field) == null;
    }

    /** The first field, in the given order, that is SPACES or LOW-VALUES. */
    @SafeVarargs
    public final Optional<F> firstBlank(F... inOrder) {
        for (F field : inOrder) {
            if (isBlank(field)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    /** {@code MOVE DFHxxx TO fC}. */
    public SymbolicMap<F> color(F field, Color color) {
        colors.put(field, color);
        return this;
    }

    /** {@code MOVE DFHxxx TO fA}. */
    public SymbolicMap<F> attribute(F field, FieldAttribute attribute) {
        attributes.put(field, attribute);
        return this;
    }

    /** {@code MOVE -1 TO fL}. */
    public SymbolicMap<F> cursor(F field) {
        cursorRequests.add(field);
        return this;
    }

    MapId<F> id() {
        return map;
    }

    String value(F field) {
        return values.get(field);
    }

    Color colorOf(F field) {
        return colors.get(field);
    }

    FieldAttribute attributeOf(F field) {
        return attributes.get(field);
    }

    Optional<F> cursorField() {
        return cursorRequests.stream().findFirst();
    }

    /** Header fields are set by name because not every map has all of them. */
    void setIfPresent(String fieldName, String value) {
        for (F field : map.fields()) {
            if (field.name().equals(fieldName)) {
                set(field, value);
            }
        }
    }
}
