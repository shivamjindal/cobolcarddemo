package com.carddemo.online;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.carddemo.online.maps.Maps;

/**
 * The 3270 screen of one session: per named field the shown value, the modified-data tag, protection
 * and color. The rules are those of {@code characterization/harness/screen.py}, which the goldens were
 * recorded with. Mutable; the dispatcher works on a copy and keeps it only when the task succeeds.
 */
final class Terminal implements Serializable {

    private String mapName;
    private LinkedHashMap<String, Cell> cells = new LinkedHashMap<>();
    private String cursor;
    private String text;

    private static final class Cell implements Serializable {
        String value;
        boolean mdt;
        boolean protect;
        Color color;

        Cell copy() {
            Cell copy = new Cell();
            copy.value = value;
            copy.mdt = mdt;
            copy.protect = protect;
            copy.color = color;
            return copy;
        }
    }

    Terminal copy() {
        Terminal copy = new Terminal();
        copy.mapName = mapName;
        cells.forEach((name, cell) -> copy.cells.put(name, cell.copy()));
        copy.cursor = cursor;
        copy.text = text;
        return copy;
    }

    private <F extends Enum<F> & MapField> void reset(MapId<F> map) {
        mapName = map.name();
        cells = new LinkedHashMap<>();
        for (F field : map.fields()) {
            BmsField bms = field.bms();
            Cell cell = new Cell();
            cell.value = bms.initial();
            cell.mdt = bms.has(Attribute.FSET);
            cell.protect = bms.protectedByDefault();
            cell.color = bms.color();
            cells.put(field.name(), cell);
        }
    }

    <F extends Enum<F> & MapField> void sendMap(SymbolicMap<F> out, SendMode mode) {
        MapId<F> map = out.id();
        text = null;
        if (mode.erase() || !map.name().equals(mapName)) {
            reset(map);
        }
        for (F field : map.fields()) {
            Cell cell = cells.get(field.name());
            String value = out.value(field);
            if (value != null) {
                cell.value = value;
            }
            FieldAttribute attribute = out.attributeOf(field);
            if (attribute != null) {
                cell.mdt = attribute.mdt();
                cell.protect = attribute.protect();
            }
            Color color = out.colorOf(field);
            if (color != null && color != Color.DFCOL) {
                cell.color = color;
            }
        }
        Optional<F> requested = out.cursorField();
        if (mode.cursor() && requested.isPresent()) {
            cursor = requested.get().name();
        } else if (mode.erase()) {
            cursor = map.fields().stream().filter(f -> f.bms().has(Attribute.IC)).findFirst().map(Enum::name).orElse(null);
        }
    }

    void sendText(String message) {
        mapName = null;
        cells = new LinkedHashMap<>();
        cursor = null;
        text = message.stripTrailing();
    }

    /**
     * The user types into fields and presses a key. Returns what RECEIVE MAP would see, or empty for
     * MAPFAIL: a CLEAR or PA key, or no field with MDT on.
     *
     * @throws BadInput when the typing is impossible on this screen
     */
    <F extends Enum<F> & MapField> Optional<SymbolicMap<F>> typeAndSend(MapId<F> expected, Key aid,
            Map<String, String> typed) {
        if (mapName == null) {
            if (typed != null && typed.values().stream().anyMatch(v -> v != null)) {
                throw new BadInput("no map on screen to type into");
            }
            return Optional.empty();
        }
        if (!mapName.equals(expected.name())) {
            throw new IllegalStateException("terminal shows " + mapName + " but the program owns " + expected.name());
        }
        if (typed != null) {
            for (var entry : typed.entrySet()) {
                type(expected, entry.getKey(), entry.getValue());
            }
        }
        if (!aid.sendsData()) {
            return Optional.empty();
        }
        EnumMap<F, String> transmitted = new EnumMap<>(expected.type());
        for (F field : expected.fields()) {
            Cell cell = cells.get(field.name());
            if (cell.mdt) {
                String value = cell.value;
                transmitted.put(field, value.length() > field.length() ? value.substring(0, field.length()) : value);
            }
        }
        return transmitted.isEmpty() ? Optional.empty() : Optional.of(SymbolicMap.received(expected, transmitted));
    }

    private <F extends Enum<F> & MapField> void type(MapId<F> map, String name, String value) {
        Cell cell = cells.get(name);
        if (cell == null) {
            throw new BadInput("field " + name + " is not on map " + map.name());
        }
        if (value == null) {
            return;
        }
        if (cell.protect) {
            throw new BadInput("field " + name + " is protected");
        }
        if (value.length() > map.field(name).length()) {
            throw new BadInput("field " + name + " holds " + map.field(name).length() + " characters");
        }
        if (value.chars().anyMatch(c -> c < 0x20 || c > 0xff)) {
            throw new BadInput("field " + name + " has a character a 3270 cannot enter");
        }
        cell.value = value;
        cell.mdt = true;
    }

    Optional<MapId<?>> map() {
        return mapName == null ? Optional.empty() : Maps.ALL.stream().filter(m -> m.name().equals(mapName)).findFirst();
    }

    Optional<String> text() {
        return Optional.ofNullable(text);
    }

    String cursor() {
        return cursor;
    }

    /** Every field whose shown value is not blank, in map order. */
    Map<String, String> shownValues() {
        Map<String, String> shown = new LinkedHashMap<>();
        cells.forEach((name, cell) -> {
            if (!cell.value.isBlank()) {
                shown.put(name, cell.value);
            }
        });
        return shown;
    }

    Map<String, Color> colors() {
        Map<String, Color> colors = new LinkedHashMap<>();
        cells.forEach((name, cell) -> {
            if (cell.color != null) {
                colors.put(name, cell.color);
            }
        });
        return colors;
    }

    List<String> protectedFields() {
        List<String> names = new ArrayList<>();
        cells.forEach((name, cell) -> {
            if (cell.protect) {
                names.add(name);
            }
        });
        return names;
    }

    static final class BadInput extends RuntimeException {
        BadInput(String message) {
            super(message);
        }
    }
}
