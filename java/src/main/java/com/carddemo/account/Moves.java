package com.carddemo.account;

import com.carddemo.online.MapField;
import com.carddemo.online.SymbolicMap;

/** MOVEs of record text into a map; the stores give an all-LOW-VALUES field as null. */
final class Moves {

    private Moves() {}

    static <F extends Enum<F> & MapField> SymbolicMap<F> move(SymbolicMap<F> map, F field, String value) {
        return value == null ? map.lowValue(field) : map.set(field, value);
    }
}
