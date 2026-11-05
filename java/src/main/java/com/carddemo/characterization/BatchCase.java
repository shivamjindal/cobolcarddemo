package com.carddemo.characterization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Spool;
import com.carddemo.batch.utilities.SortSpec;
import com.carddemo.jobs.JdbcJobSubmissions;
import com.carddemo.jobs.JobQueue;
import com.carddemo.jobs.SubmittedJob;
import com.carddemo.records.KeyedTable;
import com.carddemo.vsam.KeyRange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One batch case through the production path. It seeds the tables, stages what
 * {@code characterization/harness/batch.py} prepares before {@code jobrun}, submits a one-step deck to TD queue
 * JOBS, waits for the internal reader to end it, and observes what the harness observes. The Adapter's batch
 * branch and BatchGoldensIT both run this class.
 */
final class BatchCase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JOB = "CHARZ";
    private static final String STEP = "STEP01";
    private static final String QUALIFIER = "AWS.M2.CARDDEMO.";
    private static final String TRXFL = QUALIFIER + "TRXFL.VSAM.KSDS";
    private static final int CARD = 80;
    private static final Duration TIMEOUT = Duration.ofSeconds(300);
    private static final long POLL_MILLIS = 50;

    private final JsonNode testCase;
    private final JsonNode registry;
    private final Path repo;

    private BatchCase(JsonNode testCase, JsonNode registry, Path repo) {
        this.testCase = testCase;
        this.registry = registry;
        this.repo = repo;
    }

    /** A {@code run.py} request: its {@code case} and the dataset registry at {@code datasets}. */
    static BatchCase of(JsonNode request) throws IOException {
        Path registryFile = Path.of(request.get("datasets").asText()).toAbsolutePath();
        return new BatchCase(request.get("case"), JSON.readTree(registryFile.toFile()),
                registryFile.getParent().getParent().getParent());
    }

    /** The case's observation, in the shape {@code batch.run_job} returns. */
    Map<String, Object> run(DataSource db, Clock clock) throws IOException, SQLException, InterruptedException {
        JdbcTemplate template = new JdbcTemplate(db);
        JdbcClient jdbc = JdbcClient.create(template);
        DatasetStore datasets = new DatasetStore(jdbc, template,
                new TransactionTemplate(new DataSourceTransactionManager(db)), clock);
        JsonNode setup = testCase.path("setup");
        JsonNode job = testCase.get("job");
        setup.fieldNames().forEachRemaining(name -> {
            if (!SeededTable.SEEDED.contains(name) && !sequential(name)) {
                throw new IllegalArgumentException("the java target seeds " + SeededTable.SEEDED
                        + " and sequential datasets; the case sets up " + name);
            }
        });
        List<SeededTable> tables = SeededTable.all(registry, repo);
        try (Connection connection = db.getConnection()) {
            SeededTable.seed(connection, tables, setup);
            List<String> deck = stage(job, setup, connection, tables, datasets);
            List<TreeMap<String, byte[]>> before = new ArrayList<>();
            for (SeededTable table : tables) {
                before.add(table.snapshot(connection));
            }
            if (!new JdbcJobSubmissions(jdbc, clock).submit(new SubmittedJob("JOBS", JOB, null, null, null, deck, JOB))) {
                throw new IllegalStateException("TD queue JOBS refused the deck");
            }
            JobQueue.Run ended = await(new JobQueue(jdbc, clock),
                    jdbc.sql("select max(id) from job_submissions").query(Long.class).single());

            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("completion", ended.completion().orElseThrow());
            Spool spool = new Spool(jdbc, template);
            OptionalLong execution = ended.jobExecutionId();
            StringBuilder stdout = new StringBuilder();
            for (byte[] line : spooled(spool, execution, Spool.DISPLAY)) {
                stdout.append(new String(line, ISO_8859_1)).append('\n');
            }
            observation.put("sysout", Python.splitlines(stdout.toString()).stream().map(Python::rstrip).toList());
            Map<String, Object> files = new LinkedHashMap<>();
            for (int i = 0; i < tables.size(); i++) {
                SeededTable table = tables.get(i);
                table.diff(before.get(i), table.snapshot(connection)).ifPresent(d -> files.put(table.name(), d));
            }
            if (!files.isEmpty()) {
                observation.put("files", files);
            }
            Map<String, Object> outputs = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> dd : job.get("dd").properties()) {
                if (dd.getValue().has("output")) {
                    outputs.put(dd.getValue().get("output").asText(),
                            output(dd.getValue(), spooled(spool, execution, dd.getKey())));
                }
            }
            if (!outputs.isEmpty()) {
                observation.put("outputs", outputs);
            }
            return observation;
        }
    }

    /**
     * The deck, after staging each DD's data the way the harness does:
     * <pre>
     *   "DATASET" keyed          DSN=&lt;its cluster&gt;,DISP=SHR
     *   "DATASET" sequential     DSN=AWS.M2.CARDDEMO.&lt;name&gt;.PS,DISP=SHR, staged from setup or seed
     *   {"dataset": X}           DSN=AWS.M2.CARDDEMO.CHARZ.&lt;dd&gt;,DISP=SHR, a key-ordered flat copy of X
     *   {"lines", reclen 80}     DD * with the lines
     *   {"derive": "creastmt-trxfl"}  DSN=AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS,DISP=SHR, staged through CREASTMT's sort
     *   {"output", reclen|recfm} DD SYSOUT=*,DCB=(RECFM=F,LRECL=reclen) or DCB=(RECFM=V)
     * </pre>
     */
    private List<String> stage(JsonNode job, JsonNode setup, Connection connection, List<SeededTable> tables,
            DatasetStore datasets) throws IOException, SQLException {
        List<String> deck = new ArrayList<>();
        deck.add("//" + JOB + " JOB '" + testCase.get("id").asText() + "'");
        String parm = job.path("parm").asText("");
        deck.add("//" + STEP + " EXEC PGM=" + job.get("program").asText()
                + (parm.isEmpty() ? "" : ",PARM='" + parm.replace("'", "''") + "'"));
        if (!job.get("dd").has(Spool.DISPLAY)) {
            deck.add("//" + Spool.DISPLAY + " DD SYSOUT=*");
        }
        for (Map.Entry<String, JsonNode> dd : job.get("dd").properties()) {
            String name = dd.getKey();
            JsonNode spec = dd.getValue();
            if (spec.isTextual() && !sequential(spec.asText())) {
                deck.add(dsn(name, KeyedTable.valueOf(spec.asText()).dsn()));
            } else if (spec.isTextual()) {
                String dsn = QUALIFIER + spec.asText() + ".PS";
                datasets.stage(dsn, fixed(spec.asText()), Optional.empty(), sequentialRecords(spec.asText(), setup));
                deck.add(dsn(name, dsn));
            } else if (spec.has("dataset")) {
                String source = spec.get("dataset").asText();
                String dsn = QUALIFIER + JOB + "." + name;
                datasets.stage(dsn, fixed(source), Optional.empty(), sequential(source)
                        ? sequentialRecords(source, setup)
                        : List.copyOf(table(tables, source).snapshot(connection).values()));
                deck.add(dsn(name, dsn));
            } else if (spec.has("lines")) {
                if (spec.path("reclen").asInt() != CARD) {
                    throw new IllegalArgumentException(name + ": instream cards are " + CARD + " bytes, not " + spec.get("reclen"));
                }
                deck.add("//" + name + " DD *");
                spec.get("lines").forEach(line -> deck.add(line.asText()));
                deck.add("/*");
            } else if (spec.path("derive").asText().equals("creastmt-trxfl")) {
                List<byte[]> transactions = List.copyOf(table(tables, "TRANSACT").snapshot(connection).values());
                datasets.stage(TRXFL, new Dcb(Dcb.Recfm.F, 350), Optional.of(new KeyRange(0, 32)),
                        new SortSpec.CreastmtTrxfl().apply(transactions));
                deck.add(dsn(name, TRXFL));
            } else if (spec.has("output")) {
                deck.add("//" + name + " DD SYSOUT=*," + (spec.path("recfm").asText("F").equals("V")
                        ? "DCB=(RECFM=V)"
                        : "DCB=(RECFM=F,LRECL=" + spec.get("reclen").asInt() + ")"));
            } else {
                throw new IllegalArgumentException(name + ": unsupported DD " + spec);
            }
        }
        return deck;
    }

    private static JobQueue.Run await(JobQueue queue, long submission) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            Optional<JobQueue.Run> run = queue.run(submission);
            if (run.flatMap(JobQueue.Run::endedAt).isPresent()) {
                return run.get();
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("submission " + submission + " did not end within " + TIMEOUT
                        + (run.isEmpty() ? "; no internal reader claimed it" : ""));
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    /** {@code batch._records} and the decode that follows it. */
    private List<Object> output(JsonNode spec, List<byte[]> written) throws IOException {
        List<byte[]> records = written;
        if (!spec.path("recfm").asText("F").equals("V")) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            written.forEach(raw::writeBytes);
            byte[] bytes = raw.toByteArray();
            int length = spec.get("reclen").asInt();
            records = new ArrayList<>();
            for (int i = 0; i < bytes.length; i += length) {
                records.add(Arrays.copyOfRange(bytes, i, Math.min(i + length, bytes.length)));
            }
        }
        List<Object> rows = new ArrayList<>();
        if (spec.has("decode_as")) {
            HarnessLayout layout = HarnessLayout.of(repo, registry, spec.get("decode_as").asText());
            for (byte[] record : records) {
                byte[] padded = record;
                if (record.length < layout.recordLength()) {
                    padded = Arrays.copyOf(record, layout.recordLength());
                    Arrays.fill(padded, record.length, padded.length, (byte) ' ');
                }
                rows.add(layout.decode(padded));
            }
        } else if (spec.path("hex").asBoolean()) {
            records.forEach(record -> rows.add(HexFormat.of().formatHex(record)));
        } else {
            records.forEach(record -> rows.add(Python.rstrip(new String(record, ISO_8859_1))));
        }
        return rows;
    }

    private static List<byte[]> spooled(Spool spool, OptionalLong execution, String dd) {
        return execution.isPresent() ? spool.read(execution.getAsLong(), STEP, dd) : List.of();
    }

    private boolean sequential(String dataset) {
        JsonNode entry = registry.path("datasets").path(dataset);
        return entry.isObject() && entry.path("key").isNull();
    }

    /** {@code Datasets.materialize} of a sequential dataset: the seed or {@code replace}, then {@code append}. */
    private List<byte[]> sequentialRecords(String dataset, JsonNode setup) throws IOException {
        JsonNode entry = registry.path("datasets").path(dataset);
        JsonNode edit = setup.path(dataset);
        HarnessLayout layout = HarnessLayout.of(repo, registry, dataset);
        List<byte[]> records = new ArrayList<>();
        if (edit.has("replace")) {
            edit.get("replace").forEach(r -> records.add(layout.encode(r, null)));
        } else {
            records.addAll(SeededTable.seedRecords(repo.resolve(entry.get("source").asText()), entry.get("reclen").asInt()));
        }
        edit.path("append").forEach(r -> records.add(layout.encode(r, null)));
        return records;
    }

    private Dcb fixed(String dataset) {
        return new Dcb(Dcb.Recfm.F, registry.path("datasets").path(dataset).get("reclen").asInt());
    }

    private static SeededTable table(List<SeededTable> tables, String name) {
        return tables.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name + " is not a seeded table"));
    }

    private static String dsn(String dd, String dsn) {
        return "//" + dd + " DD DSN=" + dsn + ",DISP=SHR";
    }
}
