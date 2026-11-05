package com.carddemo.dli;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** EXEC DLI on DBPAUTP0. Each test empties the database inside a transaction the test framework rolls back. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class PcbIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private Dbpautp0 database;

    @Autowired
    private JdbcClient jdbc;

    private Pcb pcb;

    @BeforeEach
    void emptyDatabase() {
        jdbc.sql("DELETE FROM " + SegmentTables.SUMMARIES).update();
        pcb = database.schedule();
    }

    @Test
    void readsTwinsInKeyOrderAndGnWrapsAfterGb() {
        load(2, 1);
        load(1, 3);

        assertEquals(List.of("1", "1/1", "1/2", "1/3", "2", "2/1"), walk(Ssa.summary(), Ssa.detail()));
        assertEquals(DliStatus.GB, pcb.status());
        assertEquals(1L, ((PendingAuthSummary) pcb.gn(Ssa.summary()).segment().orElseThrow()).acctId());
    }

    @Test
    void gnpStaysUnderTheParentageAndEndsWithGe() {
        load(1, 2);
        load(2, 1);

        assertEquals(DliStatus.GP, pcb.gnp(Ssa.detail()).status());
        assertEquals(DliStatus.OK, pcb.gu(Ssa.summary(1)).status());
        assertEquals(key(1), pcb.gnp(Ssa.detail()).segment().orElseThrow().key());
        assertEquals(key(2), pcb.gnp(Ssa.detail()).segment().orElseThrow().key());
        assertEquals(DliStatus.GE, pcb.gnp(Ssa.detail()).status());
        assertEquals(DliStatus.GE, pcb.gu(Ssa.summary(9)).status());
        assertEquals(DliStatus.GP, pcb.gnp(Ssa.detail()).status());
    }

    @Test
    void qualifiedGetsFindByKey() {
        load(1, 2);

        assertEquals(key(2), pcb.gu(Ssa.detail(key(2))).segment().orElseThrow().key());
        assertEquals(DliStatus.GE, pcb.gn(Ssa.summary(1)).status());
        assertEquals(DliStatus.GE, pcb.gu(Ssa.detail(key(7))).status());
        assertEquals(DliStatus.OK, pcb.gn(Ssa.summary(1)).status());
    }

    @Test
    void dletAfterGnpEndedWithGeIsDj() {
        load(1, 1);
        pcb.gu(Ssa.summary(1));
        PendingAuthDetail last = pcb.gnp(Ssa.detail()).segment().orElseThrow();
        assertEquals(DliStatus.GE, pcb.gnp(Ssa.detail()).status());

        assertEquals(DliStatus.DJ, pcb.dlet(last));
        assertEquals(1, details());
    }

    @Test
    void replNeedsTheGetJustBeforeIt() {
        load(1, 0);
        PendingAuthSummary root = PendingAuthSummary.initialized(1, 501);
        assertEquals(DliStatus.DJ, pcb.repl(root));

        PendingAuthSummary held = (PendingAuthSummary) pcb.gu(Ssa.summary(1)).segment().orElseThrow();
        PendingAuthSummary changed = withCount(held, 7);
        assertEquals(DliStatus.OK, pcb.repl(changed));
        assertEquals(DliStatus.DJ, pcb.repl(changed));
        assertEquals(changed, pcb.gu(Ssa.summary(1)).segment().orElseThrow());

        pcb.gu(Ssa.summary(1));
        assertEquals(DliStatus.DA, pcb.repl(PendingAuthSummary.initialized(2, 501)));
        pcb.gu(Ssa.summary(1));
        assertEquals(DliStatus.DJ, pcb.repl(detail(1)));
    }

    @Test
    void isrtOfAKeyAlreadyThereIsIi() {
        load(1, 1);

        assertEquals(DliStatus.II, pcb.isrt(PendingAuthSummary.initialized(1, 501)));
        assertEquals(DliStatus.II, pcb.isrt(Ssa.summary(1), detail(1)));
        assertEquals(DliStatus.GE, pcb.isrt(Ssa.summary(9), detail(1)));
        assertEquals(DliStatus.OK, pcb.isrt(Ssa.summary(1), detail(2)));
        assertEquals(DliStatus.OK, pcb.isrt(detail(3)));
        assertEquals(3, details());
    }

    @Test
    void dletOfARootDeletesItsDependents() {
        load(1, 3);
        load(2, 1);

        PendingAuthSummary root = (PendingAuthSummary) pcb.gu(Ssa.summary(1)).segment().orElseThrow();
        assertEquals(DliStatus.OK, pcb.dlet(root));
        assertEquals(1, details());
        assertFalse(pcb.gu(Ssa.summary(1)).ok());

        PendingAuthDetail child = pcb.gu(Ssa.detail(key(1))).segment().orElseThrow();
        assertEquals(DliStatus.OK, pcb.dlet(child));
        assertEquals(0, details());
    }

    private void load(long acctId, int children) {
        assertEquals(DliStatus.OK, pcb.isrt(PendingAuthSummary.initialized(acctId, 500 + (int) acctId)));
        for (int i = children; i >= 1; i--) {
            assertEquals(DliStatus.OK, pcb.isrt(Ssa.summary(acctId), detail(i)));
        }
    }

    private List<String> walk(Ssa.Summary roots, Ssa.Detail children) {
        List<String> seen = new ArrayList<>();
        pcb = database.schedule();
        while (true) {
            Get<PendingAuthSummary> root = pcb.gn(roots);
            if (!root.ok()) {
                return seen;
            }
            long acctId = root.segment().orElseThrow().acctId();
            seen.add(Long.toString(acctId));
            for (Get<PendingAuthDetail> child = pcb.gnp(children); child.ok(); child = pcb.gnp(children)) {
                seen.add(acctId + "/" + child.segment().orElseThrow().key().authTime9c());
            }
        }
    }

    private int details() {
        return jdbc.sql("SELECT count(*) FROM " + SegmentTables.DETAILS).query(Integer.class).single();
    }

    private static AuthKey key(int n) {
        return new AuthKey(99999, n);
    }

    private static PendingAuthSummary withCount(PendingAuthSummary s, int approved) {
        return new PendingAuthSummary(s.acctId(), s.custId(), s.authStatus(), s.accountStatus(), s.creditLimit(),
                s.cashLimit(), s.creditBalance(), s.cashBalance(), approved, s.declinedAuthCnt(),
                s.approvedAuthAmt(), s.declinedAuthAmt(), s.filler());
    }

    private static PendingAuthDetail detail(int n) {
        BigDecimal amount = new BigDecimal("12.34");
        return new PendingAuthDetail(key(n), "260101", "120000", "4111111111111111", "PURC", "1228", "0100",
                "POS", "A0000" + n, "00", "0000", 0, amount, amount, "5411", "840", 1, "M1", "GROCER", "AUSTIN",
                "TX", "78701", "T" + n, "P", "N", "", "");
    }
}
