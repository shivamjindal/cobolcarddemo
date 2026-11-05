package com.carddemo.batch.chains;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.library.LibraryJob;
import com.carddemo.batch.programs.Cobswait;
import com.carddemo.batch.runtime.Completion;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.utilities.SortSpec;
import com.carddemo.characterization.SeededTable;
import com.carddemo.cics.CicsFile;
import com.carddemo.cics.CicsRegion;
import com.carddemo.records.Fields;
import com.carddemo.records.KeyedTable;
import com.carddemo.records.RecordImage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class RunnableChainsIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REGISTRY = Path.of("..", "characterization", "fixtures", "datasets.json");
    private static final Path STATEMENTS = Path.of("..", "characterization", "cases", "batch", "CBSTM03A.json");
    private static final String TRXFL = "AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS";
    private static final String STATEMNT_PS = "AWS.M2.CARDDEMO.STATEMNT.PS";
    private static final String STATEMNT_HTML = "AWS.M2.CARDDEMO.STATEMNT.HTML";
    private static final String DALYTRAN = "AWS.M2.CARDDEMO.DALYTRAN.PS";
    private static final LocalDate DAY = LocalDate.of(2080, 1, 1).plusDays(Math.floorMod(System.nanoTime() >>> 8, 20000));
    private static final String RUN = Long.toString(System.nanoTime(), 36).toUpperCase();
    private static final Set<CicsFile> CLOSEFIL_FILES =
            EnumSet.of(CicsFile.TRANSACT, CicsFile.CCXREF, CicsFile.ACCTDAT, CicsFile.CXACAIX, CicsFile.USRSEC);
    private static final List<Set<CicsFile>> closedAtWait = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Waits {
        @Bean
        Cobswait.Sleeper recordingSleeper(CicsRegion region) {
            return duration -> closedAtWait.add(EnumSet.copyOf(region.closed()));
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private ChainRunner runner;

    @Autowired
    private Chains chains;

    @Autowired
    private CicsRegion region;

    @Autowired
    private DatasetStore datasets;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    private int dates;

    @BeforeEach
    void freshSeed() throws Exception {
        closedAtWait.clear();
        seed(JSON.createObjectNode());
    }

    @AfterEach
    void restoreSeed() throws Exception {
        seed(JSON.createObjectNode());
    }

    @Test
    void dailyBackupThenMonthlyInterestMergesTheEmptiedMaster() throws Exception {
        int seeded = count("transactions");
        LocalDate date = day();

        ChainRun daily = start("CONTROLM_DAILY_BACKUP", date);

        assertTrue(daily.completed(), labels(daily).toString());
        assertEquals(List.of("RC=0", "RC=0", "RC=0", "RC=0"), labels(daily));
        assertClosedThenOpen();
        assertEquals(0, count("transactions"));
        List<byte[]> backup = datasets.generation(Gdg.TRANSACT_BKUP.base(), 0).orElseThrow();
        assertEquals(seeded, backup.size());

        ChainRun monthly = start("CONTROLM_MONTHLY_INTEREST", date);

        assertTrue(monthly.completed(), labels(monthly).toString());
        assertEquals(List.of("RC=0", "RC=0", "RC=0", "RC=0", "RC=0"), labels(monthly));
        assertClosedThenOpen();
        List<byte[]> interest = datasets.generation(Gdg.SYSTRAN.base(), 0).orElseThrow();
        List<byte[]> master = datasets.readNamed(KeyedTable.TRANSACT.dsn()).orElseThrow();
        assertFalse(interest.isEmpty());
        assertEquals(backup.size() + interest.size(), master.size());
        assertTrue(ids(master).containsAll(ids(backup)));
        assertTrue(ids(master).containsAll(ids(interest)));
    }

    @Test
    void postingPostsTheStagedDailyFileAndReopens() throws Exception {
        datasets.stage(DALYTRAN, new Dcb(Dcb.Recfm.F, 350), Optional.empty(),
                List.of(daily("T900000000000001", "9680294154603697")));

        ChainRun run = start("CA7_030_POSTING", day());

        assertEquals(List.of("RC=0", "BYPASSED", "RC=0", "RC=0", "RC=0"), labels(run));
        assertTrue(run.completed());
        assertClosedThenOpen();
        assertTrue(ids(datasets.readNamed(KeyedTable.TRANSACT.dsn()).orElseThrow()).contains("T900000000000001"));
    }

    @Test
    void statementsMatchTheGoldenForTheSameSeed() throws Exception {
        JsonNode suite = JSON.readTree(STATEMENTS.toFile());
        for (JsonNode testCase : suite.get("cases")) {
            seed(testCase);
            List<byte[]> transactions = transactions();

            ChainRun run = start("CA7_030_STATEMENTS", day());

            assertEquals(List.of("RC=0", "RC=0", "BYPASSED", "RC=0", "RC=0"), labels(run), testCase.get("id").asText());
            assertTrue(run.completed());
            assertClosedThenOpen();
            assertEquals(raw(new SortSpec.CreastmtTrxfl().apply(transactions)),
                    raw(datasets.readNamed(TRXFL).orElseThrow()), testCase.get("id").asText() + " TRXFL");
            JsonNode outputs = testCase.get("expect").get("outputs");
            assertEquals(texts(outputs.get("STMTFILE")), lines(datasets.readNamed(STATEMNT_PS).orElseThrow()),
                    testCase.get("id").asText());
            assertEquals(texts(outputs.get("HTMLFILE")), lines(datasets.readNamed(STATEMNT_HTML).orElseThrow()),
                    testCase.get("id").asText());
        }
    }

    @Test
    void extractsRunTheFourPrograms() {
        ChainRun run = start("CA7_030_EXTRACTS", day());

        assertEquals(List.of("RC=0", "RC=0", "RC=0", "RC=0", "RC=0", "RC=0", "RC=0"), labels(run));
        assertTrue(run.completed());
        assertClosedThenOpen();
    }

    @Test
    void aDeadRunIsAbandonedAndRestartGoesOnFromTheDeadMember() {
        Chain chain = new Chain("DEAD_" + RUN, Chain.Scheduler.CA7, "TEST", List.of(
                new Member.Job(LibraryJob.CLOSEFIL), new Member.Job(LibraryJob.WAITSTEP),
                new Member.Job(LibraryJob.OPENFIL)), "test", Optional.empty());
        LocalDate date = day();
        ChainRun started = runner.start(chain, date);
        assertTrue(started.completed());
        OptionalLong close = started.members().get(0).jobExecutionId();
        OptionalLong wait = started.members().get(1).jobExecutionId();
        OptionalLong open = started.members().get(2).jobExecutionId();
        rewind(chain.name(), "2.WAITSTEP");

        assertThrows(ChainRunner.Refused.class, () -> runner.start(chain, date));
        ChainRunner.Refused refused = assertThrows(ChainRunner.Refused.class, () -> runner.restart(chain, date));
        assertTrue(refused.getMessage().contains("STARTED"), refused.getMessage());

        ChainRun abandoned = runner.abandon(chain, date);
        assertEquals(List.of("RC=0", "FAILED", "RC=0"), labels(abandoned));
        assertThrows(ChainRunner.Refused.class, () -> runner.abandon(chain, date));

        ChainRun restarted = runner.restart(chain, date);
        assertTrue(restarted.completed(), labels(restarted).toString());
        assertEquals(close, restarted.members().get(0).jobExecutionId());
        assertNotEquals(wait, restarted.members().get(1).jobExecutionId());
        assertEquals(open, restarted.members().get(2).jobExecutionId());
        assertEquals(Set.of(), region.closed());
    }

    private ChainRun start(String name, LocalDate date) {
        closedAtWait.clear();
        return runner.start(chains.byName(name).orElseThrow(), date);
    }

    private void assertClosedThenOpen() {
        assertEquals(List.of(CLOSEFIL_FILES), closedAtWait);
        assertEquals(Set.of(), region.closed());
    }

    private LocalDate day() {
        return DAY.plusDays(dates++);
    }

    private void seed(JsonNode testCase) throws Exception {
        JsonNode registry = JSON.readTree(REGISTRY.toFile());
        Path repo = REGISTRY.toAbsolutePath().normalize().getParent().getParent().getParent();
        try (Connection connection = dataSource.getConnection()) {
            SeededTable.seed(connection, SeededTable.all(registry, repo), testCase);
        }
    }

    private int count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }

    private List<byte[]> transactions() {
        String columns = String.join(", ", KeyedTable.TRANSACT.columns().stream().map(column -> column.name()).toList());
        return jdbc.sql("select " + columns + " from transactions order by tran_id")
                .query((rs, row) -> RecordImage.of(KeyedTable.TRANSACT, rs)).list();
    }

    private void rewind(String chain, String step) {
        Long execution = jdbc.sql("select e.job_execution_id from batch_job_execution e"
                        + " join batch_job_instance i on i.job_instance_id = e.job_instance_id"
                        + " where i.job_name = ? order by e.job_execution_id desc limit 1")
                .param(chain).query(Long.class).single();
        jdbc.sql("update batch_job_execution set status = 'STARTED', exit_code = '', end_time = null"
                        + " where job_execution_id = ?").param(execution).update();
        jdbc.sql("update batch_step_execution set status = 'STARTED', exit_code = '', end_time = null"
                        + " where job_execution_id = ? and step_name = ?").params(execution, step).update();
    }

    private static List<String> labels(ChainRun run) {
        return run.members().stream().map(member -> member.completion().map(Completion::label).orElse("NOT RUN")).toList();
    }

    private static Set<String> ids(List<byte[]> records) {
        return Set.copyOf(records.stream().map(record -> new String(record, 0, 16, ISO_8859_1)).toList());
    }

    private static List<String> lines(List<byte[]> records) {
        return records.stream().map(record -> new String(record, ISO_8859_1).stripTrailing()).toList();
    }

    private static List<String> raw(List<byte[]> records) {
        return records.stream().map(record -> new String(record, ISO_8859_1)).toList();
    }

    private static List<String> texts(JsonNode array) {
        List<String> lines = new ArrayList<>();
        array.forEach(line -> lines.add(line.asText()));
        return lines;
    }

    private static byte[] daily(String id, String card) {
        byte[] record = Fields.spaces(350);
        Fields.text(record, 0, 16, id);
        Fields.text(record, 16, 2, "01");
        Fields.zoned(record, 18, 4, 0, false, BigDecimal.ONE);
        Fields.text(record, 22, 10, "POS TERM");
        Fields.text(record, 32, 100, "Purchase at Harness Store");
        Fields.zoned(record, 132, 11, 2, true, new BigDecimal("10.00"));
        Fields.zoned(record, 143, 9, 0, false, new BigDecimal("800000001"));
        Fields.text(record, 152, 50, "Harness Store");
        Fields.text(record, 202, 50, "Springfield");
        Fields.text(record, 252, 10, "12345");
        Fields.text(record, 262, 16, card);
        Fields.text(record, 278, 26, "2025-01-15 12:00:00.000000");
        return record;
    }
}
