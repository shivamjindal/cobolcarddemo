package com.carddemo.batch.utilities;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.DsnRef;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.jcl.StepDef;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;
import com.carddemo.batch.runtime.Spool;
import com.carddemo.records.Fields;
import com.carddemo.records.KeyedTable;
import com.carddemo.vsam.KeyRange;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class UtilityStatementsIT {

    private static final Dcb LRECL_350 = new Dcb(Dcb.Recfm.F, 350);
    private static final Dcb LRECL_8 = new Dcb(Dcb.Recfm.F, 8);
    private static final Dcb LRECL_1 = new Dcb(Dcb.Recfm.F, 1);
    private static final Disposition NEW_CATLG =
            new Disposition(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.DELETE);
    private static final String TRXFL = "AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS";
    private static final String TRANSACT = "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private BatchRuntime runtime;

    @Autowired
    private DatasetStore datasets;

    @Autowired
    private Spool spool;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void removeOutputs() {
        for (String dsn : List.of("TEST.SORT.OUT", "TEST.SORT.CAT", "TEST.SORT.ZD.OUT", "TEST.SORT.CH.OUT",
                "TEST.SORT.EDIT.OUT", "TEST.SORT.TRX.OUT", "TEST.SORT.BAD.OUT", "TEST.REPRO.TYPES",
                "TEST.REPRO.NAMED.OUT", "TEST.REPRO.KSDS", "TEST.REPRO.ORDER", "TEST.REPRO.TRAN.IN")) {
            datasets.deleteCataloged(dsn);
        }
    }

    @Test
    void sortIncludeKeepsTheWindowAndOrdersByTheSymnamesField() {
        datasets.stage("TEST.SORT.IN", LRECL_350, Optional.empty(), List.of(
                transaction("B", "2222222222222222", "2022-07-06"),
                transaction("A", "1111111111111111", "2022-01-01"),
                transaction("C", "3333333333333333", "2021-12-31")));

        Ran ran = run(sort("TEST.SORT.IN", "TEST.SORT.OUT", LRECL_350,
                List.of("TRAN-CARD-NUM,263,16,ZD", "TRAN-PROC-DT,305,10,CH", "PARM-START-DATE,C'2022-01-01'",
                        "PARM-END-DATE,C'2022-07-06'"),
                List.of(" SORT FIELDS=(TRAN-CARD-NUM,A)",
                        " INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,",
                        "         TRAN-PROC-DT,LE,PARM-END-DATE)")));

        assertEquals("RC=0", ran.completion().label());
        assertEquals(List.of("A", "B"), texts(datasets.read("TEST.SORT.OUT").orElseThrow(), 0, 1));
    }

    @Test
    void sortIsStableAcrossAConcatenatedSortin() {
        datasets.stage("TEST.SORT.A", LRECL_350, Optional.empty(),
                List.of(transaction("2", "1111111111111111", "2022-03-01")));
        datasets.stage("TEST.SORT.B", LRECL_350, Optional.empty(),
                List.of(transaction("1", "1111111111111111", "2022-04-01")));

        run(StepDef.program("STEP01", Sort.NAME,
                new Dd("SORTIN", List.of(named("TEST.SORT.A", Disposition.SHR, 0), named("TEST.SORT.B", Disposition.SHR, 0))),
                Dd.of("SYMNAMES", instream("TRAN-CARD-NUM,263,16,CH")),
                Dd.of("SYSIN", instream(" SORT FIELDS=(TRAN-CARD-NUM,A)")),
                Dd.sysout("SYSOUT"),
                Dd.of("SORTOUT", catalog("TEST.SORT.CAT", 350))));

        assertEquals(List.of("2", "1"), texts(datasets.read("TEST.SORT.CAT").orElseThrow(), 0, 1));
    }

    @Test
    void zonedOrderIsNumericAndCharacterOrderIsTheBytes() {
        datasets.stage("TEST.SORT.ZD", LRECL_1, Optional.empty(), List.of(new byte[] {'1'}, new byte[] {'J'}));

        run(sort("TEST.SORT.ZD", "TEST.SORT.ZD.OUT", LRECL_1, List.of(), List.of(" SORT FIELDS=(1,1,ZD,A)")));
        assertEquals(List.of("J", "1"), texts(datasets.read("TEST.SORT.ZD.OUT").orElseThrow(), 0, 1));

        run(sort("TEST.SORT.ZD", "TEST.SORT.CH.OUT", LRECL_1, List.of(), List.of(" SORT FIELDS=(1,1,CH,A)")));
        assertEquals(List.of("1", "J"), texts(datasets.read("TEST.SORT.CH.OUT").orElseThrow(), 0, 1));
    }

    @Test
    void zonedSymbolsSortAsSignedNumbersAndEditPrintsNoSign() {
        datasets.stage("TEST.SORT.EDIT", new Dcb(Dcb.Recfm.F, 2), Optional.empty(),
                List.of(bytes("10"), bytes("1J"), bytes("09")));

        run(sort("TEST.SORT.EDIT", "TEST.SORT.EDIT.OUT", new Dcb(Dcb.Recfm.F, 6),
                List.of("AMT,1,2,ZD"),
                List.of(" SORT FIELDS=(AMT,A)", " OUTREC FIELDS=(AMT,X,AMT,EDIT=(TTT))")));

        assertEquals(List.of("1J 011", "09 009", "10 010"),
                texts(datasets.read("TEST.SORT.EDIT.OUT").orElseThrow(), 0, 6));
    }

    @Test
    void outrecBuildsTheCreastmtRecordAndPadsItToTheSortoutLrecl() {
        byte[] input = Fields.spaces(350);
        Fields.text(input, 0, 3, "ID");
        Fields.text(input, 262, 4, "CARD");
        Fields.text(input, 278, 3, "MID");
        datasets.stage("TEST.SORT.TRX", LRECL_350, Optional.empty(), List.of(input));

        run(sort("TEST.SORT.TRX", "TEST.SORT.TRX.OUT", LRECL_350, List.of(), List.of(
                " SORT FIELDS=(263,16,CH,A,1,16,CH,A)",
                " OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)")));

        byte[] out = datasets.read("TEST.SORT.TRX.OUT").orElseThrow().getFirst();
        assertEquals(350, out.length);
        assertEquals("CARD", new String(out, 0, 4, ISO_8859_1));
        assertEquals("ID", new String(out, 16, 2, ISO_8859_1));
        assertEquals("MID", new String(out, 278, 3, ISO_8859_1));
        assertEquals(' ', out[20]);
    }

    @Test
    void anUnsupportedSortStatementEndsRc12WithAMessage() {
        datasets.stage("TEST.SORT.BAD", LRECL_1, Optional.empty(), List.of(new byte[] {'A'}));

        Ran ran = run(sort("TEST.SORT.BAD", "TEST.SORT.BAD.OUT", LRECL_1, List.of(),
                List.of(" MERGE FIELDS=(1,1,CH,A)")));

        assertEquals("RC=12", ran.completion().label());
        assertTrue(text(ran, "SYSOUT").stream().anyMatch(line -> line.contains("unsupported statement")));
        assertEquals(List.of(), datasets.read("TEST.SORT.BAD.OUT").orElse(List.of()));
    }

    @Test
    void reproCopiesAClusterToASequentialDataSetAndAGeneration() {
        jdbc.sql("insert into transaction_types (tran_type, tran_type_desc) values ('QZ', 'Widget') on conflict do nothing")
                .update();
        try {
            Ran ran = run(StepDef.program("STEP01", Idcams.NAME, Dd.sysout("SYSPRINT"),
                    Dd.shr("IN", KeyedTable.TRANTYPE.dsn()),
                    Dd.of("OUT", catalog("TEST.REPRO.TYPES", 60)),
                    Dd.of("SYSIN", instream(" REPRO INFILE(IN) OUTFILE(OUT)"))));

            assertEquals("RC=0", ran.completion().label());
            List<byte[]> copied = datasets.read("TEST.REPRO.TYPES").orElseThrow();
            List<byte[]> source = datasets.readNamed(KeyedTable.TRANTYPE.dsn()).orElseThrow();
            assertEquals(source.size(), copied.size());
            for (int i = 0; i < source.size(); i++) {
                assertArrayEquals(source.get(i), copied.get(i));
            }
            assertTrue(copied.stream().anyMatch(record -> new String(record, ISO_8859_1).startsWith("QZ")));
        } finally {
            jdbc.sql("delete from transaction_types where tran_type = 'QZ'").update();
        }
    }

    @Test
    void reproBetweenNamedDataSetsAndGenerationsKeepsTheRecords() {
        datasets.stage("TEST.REPRO.NAMED.IN", LRECL_8, Optional.empty(), List.of(bytes("ONE.....")));

        Ran ran = run(StepDef.program("STEP01", Idcams.NAME, Dd.sysout("SYSPRINT"),
                Dd.of("SYSIN", instream(" REPRO INDATASET(TEST.REPRO.NAMED.IN) OUTDATASET(TEST.REPRO.NAMED.OUT)"))));
        assertEquals("RC=12", ran.completion().label());

        datasets.stage("TEST.REPRO.NAMED.OUT", LRECL_8, Optional.empty(), List.of());
        ran = run(StepDef.program("STEP01", Idcams.NAME, Dd.sysout("SYSPRINT"),
                Dd.of("SYSIN", instream(" REPRO INDATASET(TEST.REPRO.NAMED.IN) OUTDATASET(TEST.REPRO.NAMED.OUT)"))));
        assertEquals("RC=0", ran.completion().label());
        assertEquals(List.of("ONE....."), texts(datasets.read("TEST.REPRO.NAMED.OUT").orElseThrow(), 0, 8));

        datasets.stage("TEST.REPRO.GDG.IN", LRECL_8, Optional.empty(), List.of(bytes("GDG.....")));
        run(StepDef.program("STEP01", Idcams.NAME, Dd.sysout("SYSPRINT"),
                Dd.shr("IN", "TEST.REPRO.GDG.IN"),
                Dd.of("OUT", new DdSource.Dataset(new DsnRef.Generation(Gdg.TRANSACT_DALY, 1), NEW_CATLG,
                        Optional.of(LRECL_8))),
                Dd.of("SYSIN", instream(" REPRO INFILE(IN) OUTFILE(OUT)"))));
        assertEquals(List.of("GDG....."), texts(datasets.generation(Gdg.TRANSACT_DALY.base(), 0).orElseThrow(), 0, 8));
    }

    @Test
    void reproIntoAKsdsReportsDuplicatesAndStopsAtTheErrorLimit() {
        datasets.defineWorkCluster("TEST.REPRO.KSDS", 8, new KeyRange(0, 4));
        datasets.stage("TEST.REPRO.DUPS", LRECL_8, Optional.empty(),
                List.of(bytes("AAAAAAA1"), bytes("AAAAAAA2"), bytes("BBBBBBB1"), bytes("AAAAAAA3")));

        Ran under = run(reproNamed("TEST.REPRO.DUPS", "TEST.REPRO.KSDS"));

        assertEquals("RC=8", under.completion().label());
        assertTrue(text(under, "SYSPRINT").stream().anyMatch(line -> line.contains("DUPLICATE KEY")));
        assertEquals(List.of("AAAAAAA1", "BBBBBBB1"), texts(datasets.readNamed("TEST.REPRO.KSDS").orElseThrow(), 0, 8));

        datasets.defineWorkCluster("TEST.REPRO.KSDS", 8, new KeyRange(0, 4));
        datasets.stage("TEST.REPRO.LIMIT", LRECL_8, Optional.empty(), List.of(bytes("AAAAAAA1"), bytes("AAAAAAA2"),
                bytes("AAAAAAA3"), bytes("AAAAAAA4"), bytes("AAAAAAA5"), bytes("BBBBBBB1")));
        Ran stopped = run(reproNamed("TEST.REPRO.LIMIT", "TEST.REPRO.KSDS"));
        assertEquals("RC=12", stopped.completion().label());
        assertEquals(List.of("AAAAAAA1"), texts(datasets.readNamed("TEST.REPRO.KSDS").orElseThrow(), 0, 8));
        assertFalse(texts(datasets.readNamed("TEST.REPRO.KSDS").orElseThrow(), 0, 8).contains("BBBBBBB1"));
    }

    @Test
    void reproIntoAnEmptyKsdsRejectsAKeyThatDoesNotAscend() {
        datasets.defineWorkCluster("TEST.REPRO.KSDS", 8, new KeyRange(0, 4));
        datasets.stage("TEST.REPRO.ORDER", LRECL_8, Optional.empty(),
                List.of(bytes("BBBBBBB1"), bytes("AAAAAAA1"), bytes("CCCCCCC1")));

        Ran ran = run(reproNamed("TEST.REPRO.ORDER", "TEST.REPRO.KSDS"));

        assertEquals("RC=8", ran.completion().label());
        assertEquals(List.of("BBBBBBB1", "CCCCCCC1"), texts(datasets.readNamed("TEST.REPRO.KSDS").orElseThrow(), 0, 8));
        assertTrue(text(ran, "SYSPRINT").stream().anyMatch(line -> line.contains("OUT OF SEQUENCE")));

        datasets.stage("TEST.REPRO.ORDER", LRECL_8, Optional.empty(), List.of(bytes("AAAAAAA9")));
        Ran added = run(reproNamed("TEST.REPRO.ORDER", "TEST.REPRO.KSDS"));
        assertEquals("RC=0", added.completion().label());
        assertTrue(texts(datasets.readNamed("TEST.REPRO.KSDS").orElseThrow(), 0, 8).contains("AAAAAAA9"));
    }

    @Test
    void duplicateLoadOfTheTransactionClusterLeavesTheOriginalAndSkipsTheCopy() {
        byte[] original = transactionRecord("R000000000000001", "10.00");
        byte[] again = transactionRecord("R000000000000001", "10.00");
        byte[] extra = transactionRecord("R000000000000002", "3.00");
        datasets.stage("TEST.REPRO.TRAN.IN", LRECL_350, Optional.empty(), List.of(original));
        run(reproInto(KeyedTable.TRANSACT.dsn(), "TEST.REPRO.TRAN.IN"));
        datasets.stage("TEST.REPRO.TRAN.IN", LRECL_350, Optional.empty(), List.of(again, extra));
        try {
            Ran ran = run(reproInto(KeyedTable.TRANSACT.dsn(), "TEST.REPRO.TRAN.IN"));
            assertEquals("RC=8", ran.completion().label());
            assertTrue(text(ran, "SYSPRINT").stream().anyMatch(line -> line.contains("RECORD 1 NOT WRITTEN")));
            assertTrue(datasets.readNamed(TRANSACT).orElseThrow().stream()
                    .anyMatch(record -> new String(record, 0, 16, ISO_8859_1).equals("R000000000000002")));
        } finally {
            jdbc.sql("delete from transactions where tran_id in ('R000000000000001', 'R000000000000002')").update();
        }
    }

    @Test
    void deleteDefineAndTheConditionCommandsFollowTheLibraryCards() {
        List<byte[]> saved = List.copyOf(datasets.readNamed(TRANSACT).orElseThrow());
        try {
            Ran deleted = run(idcams("""
                    DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS -
                           CLUSTER
                    IF MAXCC LE 08 THEN SET MAXCC = 0
                    DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX -
                           ALTERNATEINDEX
                    IF MAXCC LE 08 THEN SET MAXCC = 0
                    """));
            assertEquals("RC=0", deleted.completion().label());
            assertEquals(List.of(), datasets.readNamed(TRANSACT).orElseThrow());
            assertTrue(text(deleted, "SYSPRINT").stream().anyMatch(line -> line.contains("TRANSACT.VSAM.AIX DELETED")));

            Ran defined = run(idcams("""
                    DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS) -
                           CYLINDERS(1 5) -
                           VOLUMES(AWSHJ1 -
                           ) -
                           KEYS(16 0) -
                           RECORDSIZE(350 350) -
                           SHAREOPTIONS(2 3) -
                           ERASE -
                           INDEXED -
                           ) -
                           DATA (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.DATA) -
                           ) -
                           INDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.INDEX) -
                           )
                    """));
            assertEquals("RC=0", defined.completion().label());
            assertEquals(List.of(), datasets.readNamed(TRANSACT).orElseThrow());
        } finally {
            jdbc.sql("delete from transactions").update();
            saved.forEach(record -> datasets.insertCluster(KeyedTable.TRANSACT, record));
        }

        Ran created = run(idcams("""
                DELETE    AWS.M2.CARDDEMO.TRXFL.SEQ
                DELETE    AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS                     -
                          CLUSTER
                SET       MAXCC = 0
                DEFINE    CLUSTER  (NAME(AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS)     -
                                    KEYS(32 0)                                -
                                    VOLUMES(TSU023)                           -
                                    RECORDSIZE(350 350)                       -
                                    SHAREOPTIONS(2 3)                         -
                                    ERASE                                     -
                                    INDEXED                                   -
                                    CYL(1 5))                                 -
                          DATA      (NAME(AWS.M2.CARDDEMO.TRXFL.DATA)         -
                                    CISZ(4096))                               -
                          INDEX     (NAME(AWS.M2.CARDDEMO.TRXFL.INDEX))
                """));
        assertEquals("RC=0", created.completion().label());
        assertEquals(Optional.of(new KeyRange(0, 32)), datasets.keyOf(TRXFL));
        assertEquals(350, datasets.lreclOf(TRXFL));
        assertEquals(List.of(), datasets.readNamed(TRXFL).orElseThrow());

        Ran missing = run(idcams(" DELETE AWS.M2.CARDDEMO.NO.SUCH.CLUSTER CLUSTER"));
        assertEquals("RC=8", missing.completion().label());
        assertTrue(text(missing, "SYSPRINT").stream().anyMatch(line -> line.contains("NOT FOUND")));
    }

    @Test
    void anUnsupportedIdcamsStatementEndsRc12WithAMessage() {
        Ran ran = run(idcams(" PRINT INDATASET(TEST.NO.SUCH)"));

        assertEquals("RC=12", ran.completion().label());
        assertTrue(text(ran, "SYSPRINT").stream().anyMatch(line -> line.contains("unsupported statement")));
    }

    private StepDef sort(String in, String out, Dcb dcb, List<String> symbols, List<String> sysin) {
        Dd sortin = Dd.of("SORTIN", named(in, Disposition.SHR, 0));
        Dd sysinDd = Dd.of("SYSIN", instream(sysin.toArray(String[]::new)));
        Dd sortout = Dd.of("SORTOUT", new DdSource.Dataset(new DsnRef.Named(out), NEW_CATLG, Optional.of(dcb)));
        if (symbols.isEmpty()) {
            return StepDef.program("STEP01", Sort.NAME, sortin, sysinDd, Dd.sysout("SYSOUT"), sortout);
        }
        return StepDef.program("STEP01", Sort.NAME, sortin,
                Dd.of("SYMNAMES", instream(symbols.toArray(String[]::new))), sysinDd, Dd.sysout("SYSOUT"), sortout);
    }

    private static StepDef reproNamed(String in, String out) {
        return StepDef.program("STEP01", Idcams.NAME, Dd.sysout("SYSPRINT"),
                Dd.of("SYSIN", instream(" REPRO INDATASET(" + in + ") OUTDATASET(" + out + ")")));
    }

    private static StepDef reproInto(String cluster, String in) {
        return StepDef.program("STEP01", Idcams.NAME, Dd.sysout("SYSPRINT"), Dd.shr("IN", in),
                Dd.shr("OUT", cluster), Dd.of("SYSIN", instream(" REPRO INFILE(IN) OUTFILE(OUT)")));
    }

    private static StepDef idcams(String sysin) {
        return StepDef.program("STEP01", Idcams.NAME, Dd.sysout("SYSPRINT"),
                Dd.of("SYSIN", instream(sysin.strip().split("\\R"))));
    }

    private Ran run(StepDef step) {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'UTIL', ?, 'TEST', ?) returning id")
                .params(new String[] {"//UTIL JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return runtime.run(JobDef.of("UTIL", step), new RunIdentity.Submitted(id));
    }

    private List<String> text(Ran ran, String dd) {
        return spool.read(ran.jobExecutionId(), "STEP01", dd).stream()
                .map(line -> new String(line, ISO_8859_1).stripTrailing()).toList();
    }

    private static byte[] transaction(String id, String card, String date) {
        byte[] record = Fields.spaces(350);
        Fields.text(record, 0, 16, id);
        Fields.text(record, 262, 16, card);
        Fields.text(record, 304, 10, date);
        return record;
    }

    private static byte[] transactionRecord(String id, String amount) {
        byte[] record = Fields.spaces(350);
        Fields.text(record, 0, 16, id);
        Fields.text(record, 16, 2, "01");
        Fields.zoned(record, 18, 4, 0, false, BigDecimal.ONE);
        Fields.text(record, 22, 10, "POS TERM");
        Fields.text(record, 32, 100, "Purchase");
        Fields.zoned(record, 132, 11, 2, true, new BigDecimal(amount));
        Fields.zoned(record, 143, 9, 0, false, BigDecimal.ONE);
        Fields.text(record, 152, 50, "Store");
        Fields.text(record, 202, 50, "City");
        Fields.text(record, 252, 10, "12345");
        Fields.text(record, 262, 16, "1111111111111111");
        Fields.text(record, 278, 26, "2022-03-01 10:00:00.000000");
        Fields.text(record, 304, 26, "2022-03-01 11:00:00.000000");
        return record;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(ISO_8859_1);
    }

    private static List<String> texts(List<byte[]> records, int offset, int length) {
        return records.stream().map(record -> new String(record, offset, length, ISO_8859_1)).toList();
    }

    private static DdSource named(String dsn, Disposition disposition, int lrecl) {
        return new DdSource.Dataset(new DsnRef.Named(dsn), disposition,
                lrecl == 0 ? Optional.empty() : Optional.of(new Dcb(Dcb.Recfm.F, lrecl)));
    }

    private static DdSource catalog(String dsn, int lrecl) {
        return new DdSource.Dataset(new DsnRef.Named(dsn), NEW_CATLG, Optional.of(new Dcb(Dcb.Recfm.F, lrecl)));
    }

    private static DdSource.Instream instream(String... cards) {
        return new DdSource.Instream(List.of(cards));
    }
}
