package com.carddemo.online;

import java.util.List;

/** The map type of a screen that only ever sends text. */
public enum NoMap implements MapField {
    ;

    public static final MapId<NoMap> MAP = new MapId<>("", "", NoMap.class, List.of());

    @Override
    public BmsField bms() {
        throw new UnsupportedOperationException("NoMap has no fields");
    }
}
