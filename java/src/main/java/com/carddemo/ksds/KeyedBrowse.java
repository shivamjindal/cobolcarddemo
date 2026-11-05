package com.carddemo.ksds;

import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.carddemo.cics.CicsFile;
import com.carddemo.cics.CicsRegion;

/**
 * One CICS browse of a KSDS table: STARTBR GTEQ, READNEXT, READPREV, ENDBR, with the semantics of
 * {@code characterization/harness/runtime/cicsstub.c}, which recorded the goldens.
 * <ul>
 *   <li>STARTBR positions at the first key at or after the start key. A start key past the last
 *       record is NOTFND, except HIGH-VALUES, which positions at end of file.</li>
 *   <li>The first READNEXT reads the record at the position. The first READPREV reads it too, or
 *       the last record when positioned at end of file.</li>
 *   <li>A read in the other direction from the previous one reads the same record again.</li>
 *   <li>Running off either end is ENDFILE and leaves the position where it was.</li>
 *   <li>A read or ENDBR with no browse started is INVREQ.</li>
 * </ul>
 *
 * <p>This class is the only place key order is decided. It is {@code COLLATE "C"}, byte order of
 * the stored text, which is what the harness's {@code memcmp} does on ASCII data. z/OS compares
 * EBCDIC, where digits sort after letters and lowercase before uppercase; see the mapping doc.
 * Keys are stored without trailing blanks, which orders like the blank-padded VSAM key only because
 * the terminal accepts no character below blank. Each read is one keyset query in the request's
 * transaction, so the browse holds no cursor.
 */
public final class KeyedBrowse<R> {

    private static final Logger log = LoggerFactory.getLogger(KeyedBrowse.class);

    /** RIDFLD of a STARTBR. */
    public sealed interface StartKey {
        StartKey LOW_VALUES = new LowValues();
        StartKey HIGH_VALUES = new HighValues();

        /** A key image without its trailing blanks. */
        static StartKey at(String key) {
            return new At(key);
        }

        record LowValues() implements StartKey {}

        record HighValues() implements StartKey {}

        record At(String key) implements StartKey {}
    }

    /** RESP of a STARTBR. */
    public enum Start { NORMAL, NOTFND, FAILED }

    /** RESP of a READNEXT or READPREV. */
    public sealed interface Read<R> {
        record Normal<R>(R record) implements Read<R> {}

        record EndFile<R>() implements Read<R> {}

        /** INVREQ, or the database failed. */
        record Failed<R>() implements Read<R> {}
    }

    /** A keyed table: the CICS file it is, its name, key column, selected columns and row mapping. */
    public record Table<R>(CicsFile file, String name, String keyColumn, String columns, RowMapper<R> rows,
            Function<R, String> key) {}

    private enum Direction { NONE, NEXT, PREV }

    private final JdbcClient jdbc;
    private final CicsRegion region;
    private final Table<R> table;
    private boolean browsing;
    private StartKey start;
    private R current;
    private Direction direction = Direction.NONE;

    public KeyedBrowse(JdbcClient jdbc, CicsRegion region, Table<R> table) {
        this.jdbc = jdbc;
        this.region = region;
        this.table = table;
    }

    /** {@code STARTBR RIDFLD(key) GTEQ}. A closed file is NOTOPEN, which every program handles as FAILED. */
    public Start start(StartKey key) {
        try {
            if (!region.isOpen(table.file())) {
                browsing = false;
                return Start.FAILED;
            }
            boolean pastEnd = switch (key) {
                case StartKey.LowValues low -> edge("asc").isEmpty();
                case StartKey.HighValues high -> false;
                case StartKey.At at -> first(">=", at.key()).isEmpty();
            };
            if (pastEnd) {
                browsing = false;
                return Start.NOTFND;
            }
        } catch (DataAccessException e) {
            log.warn("STARTBR {} failed", table.name(), e);
            browsing = false;
            return Start.FAILED;
        }
        browsing = true;
        start = key;
        current = null;
        direction = Direction.NONE;
        return Start.NORMAL;
    }

    public Read<R> next() {
        return read(Direction.NEXT);
    }

    public Read<R> previous() {
        return read(Direction.PREV);
    }

    /** {@code ENDBR}. False is INVREQ: no browse was started. */
    public boolean end() {
        boolean was = browsing;
        browsing = false;
        return was;
    }

    private Read<R> read(Direction to) {
        if (!browsing) {
            return new Read.Failed<>();
        }
        Optional<R> found;
        try {
            found = current == null ? firstRead(to) : direction != to ? Optional.of(current) : step(to);
        } catch (DataAccessException e) {
            log.warn("browse {} failed", table.name(), e);
            return new Read.Failed<>();
        }
        if (found.isEmpty()) {
            return new Read.EndFile<>();
        }
        current = found.get();
        direction = to;
        return new Read.Normal<>(current);
    }

    private Optional<R> firstRead(Direction to) {
        Optional<R> atPosition = switch (start) {
            case StartKey.LowValues low -> edge("asc");
            case StartKey.HighValues high -> Optional.empty();
            case StartKey.At at -> first(">=", at.key());
        };
        return to == Direction.NEXT || atPosition.isPresent() ? atPosition : edge("desc");
    }

    private Optional<R> step(Direction to) {
        String key = table.key().apply(current);
        return to == Direction.NEXT ? first(">", key) : last("<", key);
    }

    private Optional<R> first(String comparison, String key) {
        return jdbc.sql(select() + " where " + keyC() + " " + comparison + " ? order by " + keyC() + " asc limit 1")
                .param(key).query(table.rows()).optional();
    }

    private Optional<R> last(String comparison, String key) {
        return jdbc.sql(select() + " where " + keyC() + " " + comparison + " ? order by " + keyC() + " desc limit 1")
                .param(key).query(table.rows()).optional();
    }

    private Optional<R> edge(String order) {
        return jdbc.sql(select() + " order by " + keyC() + " " + order + " limit 1").query(table.rows()).optional();
    }

    private String select() {
        return "select " + table.columns() + " from " + table.name();
    }

    private String keyC() {
        return table.keyColumn() + " collate \"C\"";
    }
}
