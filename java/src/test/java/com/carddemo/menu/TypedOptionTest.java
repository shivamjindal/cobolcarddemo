package com.carddemo.menu;

import java.util.LinkedHashMap;
import java.util.Map;

import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COADM1A;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedOptionTest {

    /** OPTION as typed, and the OPTIONO echo COADM01C sent back, observed on the legacy harness. */
    private static final Map<String, String> LEGACY = new LinkedHashMap<>();

    static {
        LEGACY.put("1", "01");
        LEGACY.put("1 ", "01");
        LEGACY.put(" 6", "06");
        LEGACY.put("06", "06");
        LEGACY.put("7", "07");
        LEGACY.put("12", "12");
        LEGACY.put("99", "99");
        LEGACY.put("0", "00");
        LEGACY.put("00", "00");
        LEGACY.put("", "00");
        LEGACY.put(" ", "00");
        LEGACY.put("  ", "00");
        LEGACY.put("A", "00");
        LEGACY.put("a", "00");
        LEGACY.put("A1", "00");
        LEGACY.put(".1", "00");
        LEGACY.put("+", "00");
        LEGACY.put("-", "00");
        LEGACY.put("1A", "01");
        LEGACY.put("1.", "01");
        LEGACY.put("1,", "01");
        LEGACY.put(",1", "01");
        LEGACY.put("-1", "01");
        LEGACY.put("+1", "01");
        LEGACY.put("1-", "01");
        LEGACY.put("1+", "01");
        LEGACY.put("-6", "06");
    }

    @Test
    void readsOptionLikeTheLegacyMenus() {
        LEGACY.forEach((typed, echo) -> assertEquals(echo, TypedOption.read(received(typed), COADM1A.OPTION).echo(),
                "typed '" + typed + "'"));
    }

    @Test
    void lowValuesReadAsZero() {
        assertEquals(0, TypedOption.read(SymbolicMap.lowValues(COADM1A.MAP), COADM1A.OPTION).number());
    }

    @Test
    void anOptionIsValidFromOneToTheTableSize() {
        assertEquals("COUSR00C", new TypedOption(1).in(MenuTables.ADMIN).orElseThrow().program().value());
        assertEquals("COTRTUPC", new TypedOption(6).in(MenuTables.ADMIN).orElseThrow().program().value());
        assertTrue(new TypedOption(0).in(MenuTables.ADMIN).isEmpty());
        assertTrue(new TypedOption(7).in(MenuTables.ADMIN).isEmpty());
    }

    /** What RECEIVE MAP yields after the user types into OPTION, which has FSET. */
    private static SymbolicMap<COADM1A> received(String typed) {
        SymbolicMap<COADM1A> map = SymbolicMap.lowValues(COADM1A.MAP);
        if (!typed.isEmpty()) {
            map.set(COADM1A.OPTION, typed);
        }
        return map;
    }
}
