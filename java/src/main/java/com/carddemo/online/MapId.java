package com.carddemo.online;

import java.util.List;

/** A BMS map: its mapset, its name, its named fields, and its unnamed literal fields in source order. */
public record MapId<F extends Enum<F> & MapField>(String mapset, String name, Class<F> type, List<BmsField> literals) {

    public List<F> fields() {
        return List.of(type.getEnumConstants());
    }

    public F field(String fieldName) {
        return Enum.valueOf(type, fieldName);
    }
}
