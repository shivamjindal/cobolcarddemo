package com.carddemo.online;

import com.carddemo.users.Credentials;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import static com.carddemo.online.SpecCase.text;

/** The users table seen as the USRSEC dataset of a spec: loaded from given.files, diffed into file_effects. */
class UsersTable {

    record Effect(String op, Map<String, String> record) {
    }

    /** bcrypt is slow on purpose; the specs reuse a handful of passwords. */
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;

    UsersTable(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void reset(List<Map<String, Object>> records) {
        jdbc.update("DELETE FROM users");
        for (Map<String, Object> r : records) {
            jdbc.update("INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)"
                            + " VALUES (?, ?, ?, ?, ?)", text(r.get("SEC-USR-ID")), text(r.get("SEC-USR-FNAME")),
                    text(r.get("SEC-USR-LNAME")), HASHES.computeIfAbsent(text(r.get("SEC-USR-PWD")), Credentials::hash),
                    text(r.get("SEC-USR-TYPE")));
        }
    }

    Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> rows = new TreeMap<>();
        jdbc.query("SELECT * FROM users", rs -> {
            rows.put(rs.getString("sec_usr_id"), Map.of("SEC-USR-ID", rs.getString("sec_usr_id"),
                    "SEC-USR-FNAME", rs.getString("sec_usr_fname"), "SEC-USR-LNAME", rs.getString("sec_usr_lname"),
                    "SEC-USR-PWD", rs.getString("sec_usr_pwd"), "SEC-USR-TYPE", rs.getString("sec_usr_type")));
        });
        return rows;
    }

    List<Effect> effectsSince(Map<String, Map<String, String>> before) {
        Map<String, Map<String, String>> after = snapshot();
        List<Effect> effects = new ArrayList<>();
        after.forEach((id, row) -> {
            if (!before.containsKey(id)) {
                effects.add(new Effect("WRITE", row));
            } else if (!before.get(id).equals(row)) {
                effects.add(new Effect("REWRITE", row));
            }
        });
        before.forEach((id, row) -> {
            if (!after.containsKey(id)) {
                effects.add(new Effect("DELETE", row));
            }
        });
        return effects;
    }

    /** given.file_conditions: every USRSEC command fails while the table is renamed away. */
    <T> T with(List<Map<String, Object>> conditions, Callable<T> action) throws Exception {
        if (conditions.isEmpty()) {
            return action.call();
        }
        if (!conditions.stream().allMatch(c -> "USRSEC".equals(c.get("file")))) {
            throw new IllegalArgumentException("Only USRSEC conditions are supported: " + conditions);
        }
        jdbc.execute("ALTER TABLE users RENAME TO users_offline");
        try {
            return action.call();
        } finally {
            jdbc.execute("ALTER TABLE users_offline RENAME TO users");
        }
    }
}
