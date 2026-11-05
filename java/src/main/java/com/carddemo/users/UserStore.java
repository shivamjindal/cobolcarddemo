package com.carddemo.users;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * USRSEC, table {@code users}. Results mirror the CICS RESP codes the programs branch on, so a
 * screen's switch is as exhaustive as its {@code EVALUATE WS-RESP-CD}. Every call runs in the
 * request's transaction, which is the CICS task.
 */
@Component
public final class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    private static final String COLUMNS = "sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type";

    private final JdbcClient jdbc;

    public UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public sealed interface Lookup permits Found, Missing, Failed {}

    public record Found(UserRecord user) implements Lookup {}

    /** NOTFND. */
    public record Missing() implements Lookup {}

    /** Any other RESP. */
    public record Failed() implements Lookup {}

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

    static UserRecord row(ResultSet rs, int rowNum) throws SQLException {
        return new UserRecord(UserId.of(rs.getString("sec_usr_id")), rs.getString("sec_usr_fname"),
                rs.getString("sec_usr_lname"), rs.getString("sec_usr_pwd"),
                UserTypeCode.stored(rs.getString("sec_usr_type")));
    }
}
