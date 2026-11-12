package com.carddemo.users;

import com.carddemo.online.UserType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * USRSEC over the users table. Browses are keyset pages in byte order (COLLATE "C"), the order of a
 * VSAM KSDS key, so STARTBR/READNEXT/READPREV become one query each.
 */
@Repository
public class UserRepository {

    private static final String COLUMNS = "sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type";
    private static final String ID = "sec_usr_id COLLATE \"C\"";

    private static final RowMapper<SecUser> ROW = (rs, n) -> new SecUser(rs.getString(1), rs.getString(2),
            rs.getString(3), rs.getString(4), UserType.stored(rs.getString(5)));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SecUser> find(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE sec_usr_id = ?", ROW, id).stream().findFirst();
    }

    /** Throws {@link org.springframework.dao.DuplicateKeyException} when the ID exists. */
    public void insert(SecUser user) {
        jdbc.update("INSERT INTO users (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                user.id(), user.firstName(), user.lastName(), user.passwordHash(), user.type().code);
    }

    public void update(SecUser user) {
        jdbc.update("UPDATE users SET sec_usr_fname = ?, sec_usr_lname = ?, sec_usr_pwd = ?, sec_usr_type = ?"
                + " WHERE sec_usr_id = ?", user.firstName(), user.lastName(), user.passwordHash(), user.type().code, user.id());
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM users WHERE sec_usr_id = ?", id) == 1;
    }

    public List<SecUser> pageFrom(String startKey, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE " + ID + " >= ? ORDER BY " + ID + " LIMIT ?",
                ROW, startKey, limit);
    }

    public List<SecUser> pageAfter(String key, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE " + ID + " > ? ORDER BY " + ID + " LIMIT ?",
                ROW, key, limit);
    }

    /** Nearest first, like READPREV. */
    public List<SecUser> pageBefore(String key, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE " + ID + " < ? ORDER BY " + ID + " DESC LIMIT ?",
                ROW, key, limit);
    }
}
