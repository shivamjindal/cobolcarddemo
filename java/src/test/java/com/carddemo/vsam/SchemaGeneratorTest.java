package com.carddemo.vsam;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaGeneratorTest {

    @Test
    void promotesDatesAndEmitsEveryForeignKey() throws IOException {
        SchemaGenerator.Generated generated = SchemaGenerator.generate(Fixtures.appRoot());
        assertEquals(
                Datasets.ALL.stream().map(Dataset::table).toList(),
                generated.tables().stream().map(table -> table.dataset().table()).toList());

        assertInstanceOf(SqlType.Date.class, column(generated, "accounts", "acct_open_date").type());
        assertInstanceOf(SqlType.Date.class, column(generated, "accounts", "acct_expiraion_date").type());
        assertInstanceOf(SqlType.Date.class, column(generated, "accounts", "acct_reissue_date").type());
        assertInstanceOf(SqlType.Date.class, column(generated, "customers", "cust_dob_yyyy_mm_dd").type());
        assertInstanceOf(SqlType.Date.class, column(generated, "cards", "card_expiraion_date").type());
        assertInstanceOf(SqlType.Timestamp.class, column(generated, "transactions", "tran_orig_ts").type());
        SqlType.Text processed = assertInstanceOf(SqlType.Text.class,
                column(generated, "transactions", "tran_proc_ts").type());
        assertEquals(26, processed.length());
        assertInstanceOf(SqlType.Text.class, column(generated, "accounts", "acct_group_id").type());
        assertInstanceOf(SqlType.BigInt.class, column(generated, "accounts", "acct_id").type());
        SqlType.Numeric balance = assertInstanceOf(SqlType.Numeric.class,
                column(generated, "accounts", "acct_curr_bal").type());
        assertEquals(12, balance.precision());
        assertEquals(2, balance.scale());
        assertTrue(balance.signed());
        assertEquals("sec_usr_filler", column(generated, "users", "sec_usr_filler").name());

        assertEquals(List.of("trancat_acct_id", "trancat_type_cd", "trancat_cd"),
                primaryKey("transaction_category_balances", generated));
        assertEquals(List.of("dis_acct_group_id", "dis_tran_type_cd", "dis_tran_cat_cd"),
                primaryKey("disclosure_groups", generated));
        assertEquals(List.of("tran_type_cd", "tran_cat_cd"), primaryKey("transaction_categories", generated));

        assertEquals(10, generated.tables().stream().mapToInt(table -> table.foreignKeys().size()).sum());
        assertEquals(3, generated.tables().stream().mapToInt(table -> table.indexes().size()).sum());
        assertTrue(generated.sql().contains(
                "acct_id bigint NOT NULL CHECK (acct_id BETWEEN 0 AND 99999999999)"));
        assertTrue(generated.sql().contains("COLLATE \"C\""));
        assertTrue(generated.sql().contains("CREATE INDEX cards_card_acct_id_aix ON cards (card_acct_id);"));
        assertTrue(generated.sql().contains(
                "CREATE INDEX card_xref_xref_acct_id_aix ON card_xref (xref_acct_id);"));
        assertTrue(generated.sql().contains(
                "CREATE INDEX transactions_tran_proc_ts_aix ON transactions (tran_proc_ts);"));
        assertFalse(generated.sql().contains("CREATE UNIQUE INDEX"));
        assertFalse(generated.tables().stream().flatMap(table -> table.notes().stream())
                .anyMatch(note -> note.startsWith("FK to")));
    }

    @Test
    void matchesTheCheckedInMigration() throws IOException {
        SchemaGenerator.Generated generated = SchemaGenerator.generate(Fixtures.appRoot());
        assertEquals(Files.readString(Path.of("src/main/resources/db/migration/V1__vsam_ksds_tables.sql")),
                generated.sql());
        assertEquals(Files.readString(Path.of("docs/schema-mapping.md")), generated.markdown());
    }

    private static Column column(SchemaGenerator.Generated generated, String table, String name) {
        return generated.tables().stream()
                .filter(item -> item.dataset().table().equals(table))
                .findFirst()
                .orElseThrow()
                .columns().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> primaryKey(String table, SchemaGenerator.Generated generated) {
        return generated.tables().stream()
                .filter(item -> item.dataset().table().equals(table))
                .findFirst()
                .orElseThrow()
                .primaryKey().stream()
                .map(Column::name)
                .toList();
    }
}
