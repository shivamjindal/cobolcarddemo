package com.carddemo.db2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.carddemo.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Db2 behavior of {@link Db2} on the V6 tables. Every test but the commit tests rolls its work back. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class Db2IT {

    private static final String INSERT_TYPE = "INSERT INTO CARDDEMO.TRANSACTION_TYPE (TR_TYPE, TR_DESCRIPTION) VALUES (?, ?)";
    private static final String DESCRIPTION_OF = "SELECT TR_DESCRIPTION FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?";
    private static final RowMapper<String> FIRST = (row, n) -> row.getString(1);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private Db2 db2;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeCommittedRows() {
        jdbc.update("DELETE FROM carddemo.transaction_type WHERE tr_type IN ('ZY', 'ZZ')");
    }

    @Test
    void searchedUpdateAndDeleteOfNoRowAreNotFound() {
        rolledBack(() -> {
            assertEquals(100, db2.execute("UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = ? WHERE TR_TYPE = ?",
                    "NOWHERE", "99").sqlcode());
            assertEquals(100, db2.execute("DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?", "99").sqlcode());
            assertEquals(new Sqlca(0, "00000", "", 1), db2.execute(
                    "UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = ? WHERE TR_TYPE = ?", "PAID", "02"));
        });
    }

    @Test
    void aFailingStatementBacksOutAloneAndTheUnitOfWorkGoesOn() {
        rolledBack(() -> {
            assertEquals(0, db2.execute(INSERT_TYPE, "ZZ", "FIRST").sqlcode());
            Sqlca duplicate = db2.execute(INSERT_TYPE, "ZZ", "AGAIN");
            assertEquals(-803, duplicate.sqlcode());
            assertEquals("23505", duplicate.sqlstate());
            assertTrue(duplicate.sqlerrmc().startsWith("duplicate key value"), duplicate.sqlerrmc());
            assertEquals(0, db2.execute(INSERT_TYPE, "ZY", "AFTER").sqlcode());
            assertEquals(Optional.of("FIRST"), db2.selectInto(DESCRIPTION_OF, FIRST, "ZZ").row());
            assertEquals(Optional.of("AFTER"), db2.selectInto(DESCRIPTION_OF, FIRST, "ZY").row());
        });
    }

    @Test
    void constraintViolationsMapToDb2Sqlcodes() {
        rolledBack(() -> {
            assertEquals(-532, db2.execute("DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?", "01").sqlcode());
            assertEquals(-530, db2.execute("INSERT INTO CARDDEMO.TRANSACTION_TYPE_CATEGORY "
                    + "(TRC_TYPE_CODE, TRC_TYPE_CATEGORY, TRC_CAT_DATA) VALUES (?, ?, ?)", "99", "0001", "ORPHAN").sqlcode());
            assertEquals(-404, db2.execute(INSERT_TYPE, "ZZ", "X".repeat(51)).sqlcode());
            assertEquals(-407, db2.execute(INSERT_TYPE, "ZZ", null).sqlcode());
            assertEquals(Optional.of("PURCHASE"), db2.selectInto(DESCRIPTION_OF, FIRST, "01").row());
        });
    }

    @Test
    void selectIntoIsNotFoundWithNoRowAndMinus811WithMore() {
        rolledBack(() -> {
            assertEquals(new Fetched<>(new Sqlca(100, "02000", "", 0), Optional.empty()),
                    db2.selectInto(DESCRIPTION_OF, FIRST, "99"));
            Fetched<String> many = db2.selectInto(
                    "SELECT TR_DESCRIPTION FROM CARDDEMO.TRANSACTION_TYPE ORDER BY TR_TYPE", FIRST);
            assertEquals(-811, many.sqlca().sqlcode());
            assertEquals(Optional.of("PURCHASE"), many.row());
        });
    }

    @Test
    void hostVariablesCompareBlankPaddedAndKeepTrailingBlanks() {
        rolledBack(() -> {
            assertEquals(0, db2.execute(INSERT_TYPE, "9 ", "SHORT" + " ".repeat(45)).sqlcode());
            assertEquals(Optional.of(50), db2.selectInto(
                    "SELECT LENGTH(TR_DESCRIPTION) FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?",
                    (row, n) -> row.getInt(1), "9").row());
            assertEquals(0, db2.selectInto(DESCRIPTION_OF, FIRST, "9 ").sqlca().sqlcode());
            assertEquals(0, db2.execute(INSERT_TYPE, "Z\0", "LOW-VALUES").sqlcode());
            assertEquals(Optional.of("LOW-VALUES"), db2.selectInto(DESCRIPTION_OF, FIRST, "Z\u0001").row());
        });
    }

    @Test
    void cursorsOpenFetchAndClose() {
        rolledBack(() -> {
            Cursor<String> types = db2.declare("C-TR-TYPE-FORWARD",
                    "SELECT TR_TYPE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE >= ? ORDER BY TR_TYPE", FIRST);
            assertEquals(-501, types.fetch().sqlca().sqlcode());
            assertEquals(0, types.open("05").sqlcode());
            assertEquals(-502, types.open("05").sqlcode());
            List<String> fetched = new ArrayList<>();
            for (Fetched<String> row = types.fetch(); row.sqlca().sqlcode() == 0; row = types.fetch()) {
                fetched.add(row.row().orElseThrow());
            }
            assertEquals(List.of("05", "06", "07"), fetched);
            assertEquals(100, types.fetch().sqlca().sqlcode());
            assertEquals(0, types.close().sqlcode());
            assertEquals(-501, types.close().sqlcode());
        });
    }

    @Test
    void theUnitOfWorkCommitsWhenItReturnsAndRollsBackWhenItThrows() {
        db2.unitOfWork(() -> db2.execute(INSERT_TYPE, "ZZ", "KEPT"));
        assertThrows(IllegalStateException.class, () -> db2.unitOfWork(() -> {
            db2.execute(INSERT_TYPE, "ZY", "LOST");
            throw new IllegalStateException("abend");
        }));
        assertEquals(List.of("ZZ"), committedTestTypes());
    }

    @Test
    void syncpointCommitsWhatTheTaskDidSoFar() {
        transactions.executeWithoutResult(task -> {
            db2.execute(INSERT_TYPE, "ZZ", "BEFORE SYNCPOINT");
            db2.syncpoint();
            db2.execute(INSERT_TYPE, "ZY", "AFTER SYNCPOINT");
            task.setRollbackOnly();
        });
        assertEquals(List.of("ZZ"), committedTestTypes());
    }

    @Test
    void statementsNeedAUnitOfWork() {
        assertThrows(IllegalStateException.class, () -> db2.execute(INSERT_TYPE, "ZZ", "NO UNIT OF WORK"));
    }

    private List<String> committedTestTypes() {
        return jdbc.queryForList("SELECT tr_type FROM carddemo.transaction_type WHERE tr_type IN ('ZY', 'ZZ') ORDER BY 1",
                String.class);
    }

    private void rolledBack(Runnable work) {
        transactions.executeWithoutResult(status -> {
            work.run();
            status.setRollbackOnly();
        });
    }
}
