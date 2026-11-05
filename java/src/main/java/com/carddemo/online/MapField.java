package com.carddemo.online;

/** Implemented by the generated map enums. Enum order is the symbolic map's field order. */
public interface MapField {

    BmsField bms();

    default int length() {
        return bms().length();
    }
}
