package com.carddemo.trantypes;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * tran_types, which is both the VSAM TRANTYPE cluster and the Db2 TRANSACTION_TYPE table.
 * A description filter is a SQL LIKE pattern. Delete of a code that a category still references
 * is refused, the Db2 DELETE RESTRICT (SQLCODE -532).
 */
@Repository
public class TranTypeRepository {

    public enum DeleteOutcome { DELETED, MISSING, RESTRICTED }

    private final JdbcTemplate jdbc;

    public TranTypeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TranType> forward(String start, String code, String descriptionLike, int limit) {
        return list(start, code, descriptionLike, limit, ">=", "ASC");
    }

    public List<TranType> backward(String start, String code, String descriptionLike, int limit) {
        return list(start, code, descriptionLike, limit, "<", "DESC");
    }

    public int count(String code, String descriptionLike) {
        Query query = filter(code, descriptionLike);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tran_types" + query.where(), Integer.class,
                query.args().toArray());
        return n == null ? 0 : n;
    }

    public Optional<TranType> find(String code) {
        List<TranType> rows = jdbc.query("SELECT tran_type, tran_type_desc FROM tran_types WHERE tran_type = ?",
                (rs, row) -> new TranType(rs.getString(1), rs.getString(2)), code);
        return rows.stream().findFirst();
    }

    /** @throws DuplicateKeyException when the code already exists */
    public void insert(TranType type) {
        jdbc.update("INSERT INTO tran_types (tran_type, tran_type_desc) VALUES (?, ?)",
                type.code(), type.description());
    }

    /** @return false when no row has that code */
    public boolean update(TranType type) {
        return jdbc.update("UPDATE tran_types SET tran_type_desc = ? WHERE tran_type = ?",
                type.description(), type.code()) > 0;
    }

    public DeleteOutcome delete(String code) {
        try {
            return jdbc.update("DELETE FROM tran_types WHERE tran_type = ?", code) > 0
                    ? DeleteOutcome.DELETED : DeleteOutcome.MISSING;
        } catch (DataIntegrityViolationException e) {
            return DeleteOutcome.RESTRICTED;
        }
    }

    private List<TranType> list(String start, String code, String descriptionLike, int limit,
                                String comparison, String direction) {
        Query query = filter(code, descriptionLike);
        List<Object> args = new ArrayList<>();
        args.add(start == null ? "" : start);
        args.addAll(query.args());
        args.add(limit);
        return jdbc.query("SELECT tran_type, tran_type_desc FROM tran_types WHERE tran_type " + comparison + " ?"
                + query.and() + " ORDER BY tran_type " + direction + " LIMIT ?",
                (rs, row) -> new TranType(rs.getString(1), rs.getString(2)), args.toArray());
    }

    private static Query filter(String code, String descriptionLike) {
        List<Object> args = new ArrayList<>();
        String and = "";
        if (code != null && !code.isBlank()) {
            and += " AND tran_type = ?";
            args.add(code);
        }
        if (descriptionLike != null && !descriptionLike.isBlank()) {
            and += " AND tran_type_desc LIKE ?";
            args.add(descriptionLike);
        }
        return new Query(and.isEmpty() ? "" : " WHERE" + and.substring(4), and, args);
    }

    private record Query(String where, String and, List<Object> args) {
    }
}
