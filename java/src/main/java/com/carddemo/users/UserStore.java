package com.carddemo.users;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.carddemo.ksds.KeyedBrowse;

/**
 * USRSEC, table {@code users}. Results mirror the CICS RESP codes the programs branch on, so a
 * screen's switch is as exhaustive as its {@code EVALUATE WS-RESP-CD}. Every call runs in the
 * request's transaction, which is the CICS task.
 */
@Component
public final class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    private static final String COLUMNS = "sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type";

    private static final KeyedBrowse.Table<UserRecord> TABLE =
            new KeyedBrowse.Table<>("users", "sec_usr_id", COLUMNS, UserStore::row, u -> u.id().value());

    private final JdbcClient jdbc;

    public UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public sealed interface Lookup permits Found, Missing, Failed {}

    public sealed interface ForUpdate permits Held, Missing, Failed {}

    public record Found(UserRecord user) implements Lookup {}

    /**
     * The record a READ UPDATE locked until the task ends. Only this store makes one, so REWRITE
     * and DELETE cannot be called without the lock CICS requires for them.
     */
    public static final class Held implements ForUpdate {
        private final UserRecord user;

        private Held(UserRecord user) {
            this.user = user;
        }

        public UserRecord user() {
            return user;
        }
    }

    /** NOTFND. */
    public record Missing() implements Lookup, ForUpdate {}

    /** Any other RESP. */
    public record Failed() implements Lookup, ForUpdate {}

    /** RESP of a REWRITE or DELETE of a held record. */
    public enum Write { DONE, FAILED }

    /** RESP of a WRITE. */
    public enum Insert { INSERTED, DUPLICATE, FAILED }

    /** {@code EXEC CICS READ DATASET(USRSEC) RIDFLD(id)}. */
    public Lookup find(UserId id) {
        try {
            return jdbc.sql("select " + COLUMNS + " from users where sec_usr_id = ?")
                    .param(id.value())
                    .query(UserStore::row)
                    .optional()
                    .<Lookup>map(Found::new)
                    .orElseGet(Missing::new);
        } catch (DataAccessException e) {
            log.warn("READ USRSEC {} failed", id, e);
            return new Failed();
        }
    }

    /** {@code EXEC CICS READ DATASET(USRSEC) RIDFLD(id) UPDATE}. */
    public ForUpdate findForUpdate(UserId id) {
        try {
            return jdbc.sql("select " + COLUMNS + " from users where sec_usr_id = ? for update")
                    .param(id.value())
                    .query(UserStore::row)
                    .optional()
                    .<ForUpdate>map(Held::new)
                    .orElseGet(Missing::new);
        } catch (DataAccessException e) {
            log.warn("READ UPDATE USRSEC {} failed", id, e);
            return new Failed();
        }
    }

    /** {@code EXEC CICS REWRITE DATASET(USRSEC)}. The FILLER keeps what was read. */
    public Write rewrite(Held held, UserRecord edited) {
        if (!edited.id().equals(held.user().id())) {
            throw new IllegalArgumentException("REWRITE changes the key " + held.user().id() + " to " + edited.id());
        }
        try {
            int rows = jdbc.sql("update users set sec_usr_fname = ?, sec_usr_lname = ?, sec_usr_pwd = ?, sec_usr_type = ?"
                            + " where sec_usr_id = ?")
                    .params(edited.firstName(), edited.lastName(), edited.password(), edited.type().toString(),
                            edited.id().value())
                    .update();
            return locked(rows, held);
        } catch (DataAccessException e) {
            log.warn("REWRITE USRSEC {} failed", held.user().id(), e);
            return Write.FAILED;
        }
    }

    /** {@code EXEC CICS DELETE DATASET(USRSEC)} of the held record. */
    public Write delete(Held held) {
        try {
            return locked(jdbc.sql("delete from users where sec_usr_id = ?").param(held.user().id().value()).update(), held);
        } catch (DataAccessException e) {
            log.warn("DELETE USRSEC {} failed", held.user().id(), e);
            return Write.FAILED;
        }
    }

    /** {@code EXEC CICS WRITE DATASET(USRSEC) RIDFLD(id)}. A new record's FILLER is spaces. */
    public Insert insert(UserRecord user) {
        try {
            int rows = jdbc.sql("insert into users (" + COLUMNS + ", sec_usr_filler) values (?, ?, ?, ?, ?, '')"
                            + " on conflict (sec_usr_id) do nothing")
                    .params(user.id().value(), user.firstName(), user.lastName(), user.password(), user.type().toString())
                    .update();
            return rows == 1 ? Insert.INSERTED : Insert.DUPLICATE;
        } catch (DataAccessException e) {
            log.warn("WRITE USRSEC {} failed", user.id(), e);
            return Insert.FAILED;
        }
    }

    /** A browse for {@code STARTBR DATASET(USRSEC)}. */
    public KeyedBrowse<UserRecord> browse() {
        return new KeyedBrowse<>(jdbc, TABLE);
    }

    private static Write locked(int rows, Held held) {
        if (rows != 1) {
            throw new IllegalStateException("held record " + held.user().id() + " is gone");
        }
        return Write.DONE;
    }

    static UserRecord row(ResultSet rs, int rowNum) throws SQLException {
        return new UserRecord(UserId.of(rs.getString("sec_usr_id")), rs.getString("sec_usr_fname"),
                rs.getString("sec_usr_lname"), rs.getString("sec_usr_pwd"),
                UserTypeCode.stored(rs.getString("sec_usr_type")));
    }
}
