package com.carddemo.batch.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.StepDef;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.KeyedDataset;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;

/** A program's view of its running step, over the step's allocations. */
final class StepRun implements Step {

    private static final int CARD = 80;

    private final StepDef def;
    private final long jobExecutionId;
    private final Map<String, List<Allocation>> dds;
    private final JdbcClient jdbc;
    private final DatasetStore store;
    private final Spool spool;
    private final Clock clock;
    private final List<byte[]> displayed = new ArrayList<>();
    private final List<BufferedOutput> outputs = new ArrayList<>();

    StepRun(StepDef def, long jobExecutionId, Map<String, List<Allocation>> dds, JdbcClient jdbc, DatasetStore store,
            Spool spool, Clock clock) {
        this.def = def;
        this.jobExecutionId = jobExecutionId;
        this.dds = dds;
        this.jdbc = jdbc;
        this.store = store;
        this.spool = spool;
        this.clock = clock;
    }

    @Override
    public String parm() {
        return def.parm();
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public void display(String line) {
        displayed.add(line.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Override
    public SequentialInput input(String dd) {
        List<Allocation> concatenation = dds.get(dd);
        if (concatenation == null) {
            return RecordsInput.failing(FileStatus.FILE_NOT_FOUND);
        }
        if (concatenation.stream().anyMatch(allocation -> allocation instanceof Allocation.Sysout)) {
            return RecordsInput.failing(FileStatus.MODE_NOT_SUPPORTED);
        }
        return new RecordsInput(() -> concatenation.stream().flatMap(allocation -> records(allocation).stream()).toList());
    }

    @Override
    public SequentialOutput output(String dd) {
        List<Allocation> concatenation = dds.get(dd);
        BufferedOutput output = switch (concatenation == null ? null : concatenation.getFirst()) {
            case null -> BufferedOutput.failing(FileStatus.FILE_NOT_FOUND);
            case Allocation.Sysout sysout -> new BufferedOutput(sysout.dcb(),
                    (records, extend) -> spool.append(jobExecutionId, def.path(), dd, records));
            case Allocation.Stored stored -> new BufferedOutput(Optional.of(stored.dataset().dcb()), (records, extend) -> {
                if (extend || stored.disposition().status() == Disposition.Status.MOD) {
                    store.append(stored.dataset().id(), records);
                } else {
                    store.replace(stored.dataset().id(), records);
                }
            });
            case Allocation.Dummy dummy -> new BufferedOutput(Optional.empty(), (records, extend) -> {});
            case Allocation.Table table -> BufferedOutput.failing(FileStatus.MODE_NOT_SUPPORTED);
            case Allocation.Instream instream -> BufferedOutput.failing(FileStatus.MODE_NOT_SUPPORTED);
        };
        outputs.add(output);
        return output;
    }

    @Override
    public KeyedDataset keyed(String dd) {
        List<Allocation> concatenation = dds.get(dd);
        return switch (concatenation == null ? null : concatenation.getFirst()) {
            case null -> KeyedFile.failing(FileStatus.FILE_NOT_FOUND);
            case Allocation.Table table -> new TableDataset(jdbc, table.table());
            case Allocation.Stored stored when stored.dataset().key().isPresent() -> new StoredKsds(
                    stored.dataset().key().get(), stored.dataset().dcb().lrecl(), store.records(stored.dataset().id()));
            default -> KeyedFile.failing(FileStatus.MODE_NOT_SUPPORTED);
        };
    }

    /** Closes what the program left open, as STOP RUN and an abend do, and spools its DISPLAY lines. */
    void end() {
        if (!displayed.isEmpty()) {
            spool.append(jobExecutionId, def.path(), Spool.DISPLAY, displayed);
        }
        outputs.forEach(BufferedOutput::closeIfOpen);
    }

    private List<byte[]> records(Allocation allocation) {
        return switch (allocation) {
            case Allocation.Table table -> TableDataset.records(jdbc, table.table());
            case Allocation.Stored stored -> store.records(stored.dataset().id());
            case Allocation.Instream instream -> instream.cards().stream().map(StepRun::card).toList();
            case Allocation.Dummy dummy -> List.of();
            case Allocation.Sysout sysout -> throw new IllegalStateException("SYSOUT is not readable");
        };
    }

    private static byte[] card(String text) {
        byte[] card = new byte[CARD];
        Arrays.fill(card, (byte) ' ');
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, card, 0, Math.min(bytes.length, CARD));
        return card;
    }
}
