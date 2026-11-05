package com.carddemo.db2;

import org.springframework.jdbc.core.RowMapper;

/**
 * A declared cursor, a Postgres cursor of the same name. OPEN of an open cursor is -502, FETCH or CLOSE of a
 * closed one -501, a FETCH past the last row +100. A SYNCPOINT closes it, as Db2 closes a cursor without
 * WITH HOLD at commit.
 */
public final class Cursor<T> {

    private static final Object[] NONE = {};

    private final Db2 db2;
    private final String name;
    private final String select;
    private final RowMapper<T> into;

    Cursor(Db2 db2, String name, String select, RowMapper<T> into) {
        this.db2 = db2;
        this.name = name;
        this.select = select;
        this.into = into;
    }

    public Sqlca open(Object... hostVariables) {
        return db2.run(Db2.Verb.OPEN, "DECLARE " + quoted() + " CURSOR FOR " + select, null, hostVariables).sqlca();
    }

    public Fetched<T> fetch() {
        return db2.run(Db2.Verb.FETCH, "FETCH NEXT FROM " + quoted(), into, NONE);
    }

    public Sqlca close() {
        return db2.run(Db2.Verb.CLOSE, "CLOSE " + quoted(), null, NONE).sqlca();
    }

    private String quoted() {
        return '"' + name + '"';
    }
}
