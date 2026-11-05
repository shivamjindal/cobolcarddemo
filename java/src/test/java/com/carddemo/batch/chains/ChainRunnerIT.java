package com.carddemo.batch.chains;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

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
import com.carddemo.batch.library.LibraryJob;
import com.carddemo.batch.programs.Cobswait;
import com.carddemo.batch.runtime.Completion;
import com.carddemo.batch.step.Abend;
import com.carddemo.cics.CicsFile;
import com.carddemo.cics.CicsRegion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class ChainRunnerIT {

    /** Spring Batch keeps every chain run in the shared test database, so each run of this class names its own. */
    private static final String RUN = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 11, 5);
    private static final Set<CicsFile> CLOSEFIL_FILES =
            EnumSet.of(CicsFile.TRANSACT, CicsFile.CCXREF, CicsFile.ACCTDAT, CicsFile.CXACAIX, CicsFile.USRSEC);

    private static final Queue<Abend> cancels = new ConcurrentLinkedQueue<>();

    @TestConfiguration
    static class Waits {
        /** Waits take no time; a queued abend ends the next wait, as an operator CANCEL does. */
        @Bean
        Cobswait.Sleeper cancellableSleeper() {
            return duration -> {
                Abend cancel = cancels.poll();
                if (cancel != null) {
                    throw cancel;
                }
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private ChainRunner runner;

    @Autowired
    private CicsRegion region;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void openAll() {
        jdbc.sql("update cics_region set closed = '{}'").update();
        cancels.clear();
    }

    @Test
    void aMemberThatAbendsLeavesTheFilesClosedAndTheRestartGoesOnFromIt() {
        Chain chain = chain("ABEND", job(LibraryJob.CLOSEFIL), job(LibraryJob.WAITSTEP), job(LibraryJob.OPENFIL));
        cancels.add(Abend.system("S222"));

        ChainRun failed = runner.start(chain, ORDER_DATE);
        assertEquals(List.of("RC=0", "S222", "NOT RUN"), labels(failed));
        assertFalse(failed.completed());
        assertEquals(CLOSEFIL_FILES, region.closed(), "OPENFIL never ran");
        assertThrows(ChainRunner.Refused.class, () -> runner.start(chain, ORDER_DATE), "a failed run restarts");

        ChainRun restarted = runner.restart(chain, ORDER_DATE);
        assertEquals(List.of("RC=0", "RC=0", "RC=0"), labels(restarted));
        assertTrue(restarted.completed());
        assertEquals(failed.members().getFirst().jobExecutionId(), restarted.members().getFirst().jobExecutionId(),
                "CLOSEFIL did not run again");
        assertEquals(Set.of(), region.closed());
        assertThrows(ChainRunner.Refused.class, () -> runner.restart(chain, ORDER_DATE));
    }

    @Test
    void aSecondStartOnTheSameOrderDateIsRefused() {
        Chain chain = chain("TWICE", job(LibraryJob.CLOSEFIL), job(LibraryJob.OPENFIL));
        assertTrue(runner.start(chain, ORDER_DATE).completed());

        ChainRunner.Refused refused = assertThrows(ChainRunner.Refused.class, () -> runner.start(chain, ORDER_DATE));
        assertTrue(refused.getMessage().contains("already started for " + ORDER_DATE), refused.getMessage());
        assertTrue(runner.start(chain, ORDER_DATE.plusDays(1)).completed());
    }

    @Test
    void anOptionalMemberIsBypassedAndTheChainGoesOn() {
        Chain chain = chain("OPTIONAL", job(LibraryJob.CLOSEFIL), new Member.Optional("CBPAUP0J", "IMS"),
                job(LibraryJob.OPENFIL));

        ChainRun run = runner.start(chain, ORDER_DATE);

        assertEquals(List.of("RC=0", "BYPASSED", "RC=0"), labels(run));
        assertEquals(OptionalLong.empty(), run.members().get(1).jobExecutionId());
        assertTrue(run.completed());
        assertEquals(Set.of(), region.closed());
    }

    @Test
    void aChainWithAnUnportedMemberDoesNotStart() {
        Chain chain = chain("UNPORTED", job(LibraryJob.CLOSEFIL), new Member.Unported("PRTCATBL", "not ported"),
                job(LibraryJob.WAITSTEP), job(LibraryJob.OPENFIL));

        ChainRunner.Refused refused = assertThrows(ChainRunner.Refused.class, () -> runner.start(chain, ORDER_DATE));

        assertTrue(refused.getMessage().contains("PRTCATBL"), refused.getMessage());
        assertEquals(Set.of(), region.closed(), "CLOSEFIL did not run");
        assertEquals(Optional.empty(), runner.run(chain, ORDER_DATE));
    }

    private static Chain chain(String name, Member... members) {
        return new Chain(name + "_" + RUN, Chain.Scheduler.CA7, "TEST", List.of(members), "test", Optional.empty());
    }

    private static Member job(LibraryJob job) {
        return new Member.Job(job);
    }

    private static List<String> labels(ChainRun run) {
        return run.members().stream().map(m -> m.completion().map(Completion::label).orElse("NOT RUN")).toList();
    }
}
