package com.carddemo.batch.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SequencedMap;

import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.DsnRef;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.jcl.StepDef;
import com.carddemo.records.KeyedTable;

/**
 * One JCL job as it runs: the catalog as the job sees it and its steps' completions. The relative generations
 * of every GDG are fixed when the job starts; the first {@code (+1)} of a base creates the job's new generation
 * and every later {@code (+1)} of that base names the same one.
 */
final class JobRun {

    static final class Unallocatable extends Exception {
        Unallocatable(String reason) {
            super(reason);
        }
    }

    private static final Dcb DEFAULT_DCB = new Dcb(Dcb.Recfm.F, 0);

    private final DatasetStore store;
    private final Map<Gdg, List<Long>> generations;
    private final Map<Gdg, Long> newGenerations = new EnumMap<>(Gdg.class);
    private final Map<String, Long> passed = new HashMap<>();
    private final SequencedMap<String, Completion> completions = new LinkedHashMap<>();

    JobRun(DatasetStore store) {
        this.store = store;
        this.generations = store.generations();
    }

    SequencedMap<String, Completion> completions() {
        return completions;
    }

    void record(String step, Completion completion) {
        completions.put(step, completion);
    }

    /** Each earlier step's return code, empty for a step that did not run to one; what COND tests. */
    Map<String, OptionalInt> returnCodes() {
        Map<String, OptionalInt> codes = new LinkedHashMap<>();
        completions.forEach((step, completion) -> codes.put(step,
                completion instanceof Completion.Rc rc ? OptionalInt.of(rc.code()) : OptionalInt.empty()));
        return codes;
    }

    /** The step's DDs, each to its concatenation in order. */
    Map<String, List<Allocation>> allocate(StepDef step, long jobExecutionId) throws Unallocatable {
        Map<String, List<Allocation>> dds = new LinkedHashMap<>();
        for (Dd dd : step.dds()) {
            List<Allocation> concatenation = new ArrayList<>();
            for (DdSource source : dd.concatenation()) {
                concatenation.add(switch (source) {
                    case DdSource.Instream instream -> new Allocation.Instream(instream.cards());
                    case DdSource.Sysout sysout -> new Allocation.Sysout(sysout.dcb());
                    case DdSource.Dummy dummy -> new Allocation.Dummy();
                    case DdSource.Dataset dataset -> dataset(step, dd, dataset, jobExecutionId);
                });
            }
            dds.put(dd.name(), concatenation);
        }
        return dds;
    }

    /** DISP at step end: the abnormal disposition when the step abended. */
    void dispose(Collection<List<Allocation>> dds, boolean abended) {
        for (List<Allocation> concatenation : dds) {
            for (Allocation allocation : concatenation) {
                if (allocation instanceof Allocation.Stored stored) {
                    dispose(stored, abended ? stored.disposition().abnormal() : stored.disposition().normal());
                }
            }
        }
    }

    /** Every data set is SMS-managed, so a kept new data set is cataloged. */
    private void dispose(Allocation.Stored stored, Disposition.End end) {
        DatasetStore.Dataset dataset = stored.dataset();
        switch (end) {
            case CATLG, KEEP -> {
                if (dataset.pending()) {
                    stored.gdg().ifPresentOrElse(gdg -> store.catalogGeneration(dataset.id(), gdg),
                            () -> store.catalog(dataset.id()));
                    passed.remove(dataset.dsn());
                }
            }
            case PASS -> {
                if (dataset.pending() && stored.gdg().isEmpty()) {
                    passed.put(dataset.dsn(), dataset.id());
                }
            }
            case UNCATLG -> {
                store.uncatalog(dataset.id());
                passed.remove(dataset.dsn());
            }
            case DELETE -> {
                store.delete(dataset.id());
                passed.remove(dataset.dsn());
            }
        }
    }

    private Allocation dataset(StepDef step, Dd dd, DdSource.Dataset source, long jobExecutionId) throws Unallocatable {
        Disposition disposition = source.disposition();
        Dcb dcb = source.dcb().orElse(DEFAULT_DCB);
        return switch (source.dsn()) {
            case DsnRef.Named named -> {
                Optional<KeyedTable> table = table(named.dsn());
                if (table.isPresent()) {
                    yield new Allocation.Table(table.get());
                }
                if (Gdg.byBase(named.dsn()).isPresent()) {
                    throw new Unallocatable(step.path() + "." + dd.name() + " names GDG base " + named.dsn()
                            + " without a generation");
                }
                Optional<DatasetStore.Dataset> existing = Optional.ofNullable(passed.get(named.dsn())).map(store::byId)
                        .or(() -> store.cataloged(named.dsn()));
                yield switch (disposition.status()) {
                    case NEW -> {
                        if (existing.isPresent()) {
                            throw new Unallocatable(named.dsn() + " already exists");
                        }
                        yield new Allocation.Stored(store.create(named.dsn(), dcb, jobExecutionId), disposition,
                                Optional.empty());
                    }
                    case MOD -> new Allocation.Stored(
                            existing.orElseGet(() -> store.create(named.dsn(), dcb, jobExecutionId)), disposition,
                            Optional.empty());
                    case OLD, SHR -> new Allocation.Stored(existing.orElseThrow(
                            () -> new Unallocatable("DATA SET " + named.dsn() + " NOT FOUND")), disposition,
                            Optional.empty());
                };
            }
            case DsnRef.Generation generation when generation.relative() > 0 -> {
                if (generation.relative() != 1) {
                    throw new Unallocatable(generation.gdg().base() + "(+" + generation.relative() + ") is not supported");
                }
                Long created = newGenerations.get(generation.gdg());
                if (created != null) {
                    yield new Allocation.Stored(store.byId(created), disposition, Optional.of(generation.gdg()));
                }
                if (disposition.status() != Disposition.Status.NEW && disposition.status() != Disposition.Status.MOD) {
                    throw new Unallocatable(generation.gdg().base() + "(+1) does not exist yet");
                }
                DatasetStore.Dataset dataset = store.create(generation.gdg().base(), dcb, jobExecutionId);
                newGenerations.put(generation.gdg(), dataset.id());
                yield new Allocation.Stored(dataset, disposition, Optional.of(generation.gdg()));
            }
            case DsnRef.Generation generation -> {
                List<Long> cataloged = generations.get(generation.gdg());
                int back = -generation.relative();
                if (back >= cataloged.size()) {
                    throw new Unallocatable("DATA SET " + generation.gdg().base() + "(" + generation.relative()
                            + ") NOT FOUND");
                }
                yield new Allocation.Stored(store.byId(cataloged.get(back)), disposition, Optional.empty());
            }
        };
    }

    private static Optional<KeyedTable> table(String dsn) {
        return KeyedTable.byDsn(dsn).or(() -> Arrays.stream(KeyedTable.values())
                .filter(t -> t.alternatePath().filter(dsn::equals).isPresent()).findFirst());
    }
}
