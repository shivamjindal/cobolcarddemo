package com.carddemo.menu;

import java.util.List;

import com.carddemo.online.MapField;
import com.carddemo.online.MapId;
import com.carddemo.online.ProgramName;
import com.carddemo.online.SymbolicMap;

/**
 * COMEN02Y and COADM02Y, copied as data. MenuTablesTest compares them with the copybooks, and fails
 * when an entry would reach a branch these menus do not port: an admin-only main option, or a DUMMY
 * program.
 */
final class MenuTables {

    record Entry(int number, String name, ProgramName program) {

        /** BUILD-MENU-OPTIONS: {@code STRING num '. ' name DELIMITED BY SIZE}. */
        String text() {
            return "%02d. %s".formatted(number, name);
        }
    }

    static final List<Entry> MAIN = List.of(
            entry(1, "Account View", "COACTVWC"),
            entry(2, "Account Update", "COACTUPC"),
            entry(3, "Credit Card List", "COCRDLIC"),
            entry(4, "Credit Card View", "COCRDSLC"),
            entry(5, "Credit Card Update", "COCRDUPC"),
            entry(6, "Transaction List", "COTRN00C"),
            entry(7, "Transaction View", "COTRN01C"),
            entry(8, "Transaction Add", "COTRN02C"),
            entry(9, "Transaction Reports", "CORPT00C"),
            entry(10, "Bill Payment", "COBIL00C"),
            entry(11, "Pending Authorization View", "COPAUS0C"));

    static final List<Entry> ADMIN = List.of(
            entry(1, "User List (Security)", "COUSR00C"),
            entry(2, "User Add (Security)", "COUSR01C"),
            entry(3, "User Update (Security)", "COUSR02C"),
            entry(4, "User Delete (Security)", "COUSR03C"),
            entry(5, "Transaction Type List/Update (Db2)", "COTRTLIC"),
            entry(6, "Transaction Type Maintenance (Db2)", "COTRTUPC"));

    private MenuTables() {}

    /** BUILD-MENU-OPTIONS: entry n into OPTN00n. */
    static <F extends Enum<F> & MapField> SymbolicMap<F> build(SymbolicMap<F> map, MapId<F> id, List<Entry> table) {
        for (Entry entry : table) {
            map.set(id.field("OPTN%03d".formatted(entry.number())), entry.text());
        }
        return map;
    }

    private static Entry entry(int number, String name, String program) {
        return new Entry(number, name, ProgramName.of(program));
    }
}
