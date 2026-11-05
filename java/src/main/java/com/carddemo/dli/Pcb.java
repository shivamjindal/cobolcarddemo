package com.carddemo.dli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.carddemo.dli.SegmentTables.Child;

/**
 * A database PCB on DBPAUTP0 for one task, as EXEC DLI uses it. Each call runs in the caller's transaction, so
 * the database changes commit at SYNCPOINT or task end and roll back with the task, and every get locks the
 * segment it returns, as PROCOPT=AP does.
 * <ul>
 *   <li>Position is the last segment a call reached; a get or ISRT moves it, a failed call mostly leaves it.
 *       GN reads on in hierarchical sequence, GNP within the parentage the last successful GU or GN set.</li>
 *   <li>A GU or GN that fails cancels parentage, so a GNP after it is GP. GN past the last segment is GB and
 *       starts the next GN from the beginning of the database.</li>
 *   <li>EXEC DLI has no GHU: GU, GN and GNP hold the segment they return. REPL and DLET need the call just
 *       before them on this PCB to have been a successful get of that segment type, or they are DJ; any
 *       other call in between, a failed get included, releases the hold. A changed key field is DA.</li>
 *   <li>DLET of a root deletes its dependents. ISRT of a key already there is II; ISRT under a parent that
 *       is not there is GE.</li>
 * </ul>
 */
public final class Pcb {

    private sealed interface Position {
        record Start() implements Position {}

        record Root(long acctId) implements Position {}

        record Dependent(long acctId, AuthKey key) implements Position {}
    }

    private static final Position START = new Position.Start();

    private final JdbcClient jdbc;
    private Position position = START;
    private Optional<Position> parentage = Optional.empty();
    private Optional<Segment> held = Optional.empty();
    private DliStatus status = DliStatus.OK;

    Pcb(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** DIBSTAT of the last call. */
    public DliStatus status() {
        return status;
    }

    /** {@code GU}: the first segment in the database that satisfies {@code ssa}. */
    public <S extends Segment> Get<S> gu(Ssa<S> ssa) {
        held = Optional.empty();
        Optional<Segment> found = switch (ssa) {
            case Ssa.Summary root -> firstRoot(OptionalLong.empty(), root.acctId()).map(this::reachedRoot);
            case Ssa.Detail child -> firstChild(START, child.key(), OptionalLong.empty()).map(this::reachedChild);
        };
        if (found.isEmpty()) {
            position = START;
        }
        return getEnded(found, true, DliStatus.GE);
    }

    /** {@code GN}: the next segment of {@code ssa}'s type after the position, in hierarchical sequence. */
    public <S extends Segment> Get<S> gn(Ssa<S> ssa) {
        held = Optional.empty();
        Optional<Segment> found = switch (ssa) {
            case Ssa.Summary root -> firstRoot(rootOf(position), root.acctId()).map(this::reachedRoot);
            case Ssa.Detail child -> firstChild(position, child.key(), OptionalLong.empty()).map(this::reachedChild);
        };
        boolean qualified = switch (ssa) {
            case Ssa.Summary root -> root.acctId().isPresent();
            case Ssa.Detail child -> child.key().isPresent();
        };
        if (found.isEmpty() && !qualified) {
            position = START;
        }
        return getEnded(found, true, qualified ? DliStatus.GE : DliStatus.GB);
    }

    /** {@code GNP}: the next dependent of the parentage after the position. */
    public <S extends Segment> Get<S> gnp(Ssa<S> ssa) {
        held = Optional.empty();
        if (parentage.isEmpty()) {
            return getEnded(Optional.empty(), false, DliStatus.GP);
        }
        Optional<Segment> found = Optional.empty();
        if (ssa instanceof Ssa.Detail child && parentage.get() instanceof Position.Root parent) {
            Optional<Position> from = switch (position) {
                case Position.Root root when root.acctId() == parent.acctId() -> Optional.of(root);
                case Position.Dependent dependent when dependent.acctId() == parent.acctId() -> Optional.of(dependent);
                default -> Optional.empty();
            };
            found = from.flatMap(start -> firstChild(start, child.key(), OptionalLong.of(parent.acctId())))
                    .map(this::reachedChild);
        }
        return getEnded(found, false, DliStatus.GE);
    }

    /** {@code REPL SEGMENT(type) FROM(segment)}. */
    public DliStatus repl(Segment segment) {
        Optional<DliStatus> refused = checkHold(segment);
        if (refused.isPresent()) {
            return ended(refused.get());
        }
        switch (segment) {
            case PendingAuthSummary root -> jdbc.sql("UPDATE " + SegmentTables.SUMMARIES + " SET ("
                    + SegmentTables.SUMMARY_COLUMNS + ") = (" + placeholders(17) + ") WHERE acct_id = ?")
                    .params(append(SegmentTables.values(root), root.acctId())).update();
            case PendingAuthDetail detail -> {
                Position.Dependent at = (Position.Dependent) position;
                jdbc.sql("UPDATE " + SegmentTables.DETAILS + " SET (" + SegmentTables.DETAIL_COLUMNS + ") = ("
                        + placeholders(29) + ") WHERE acct_id = ? AND auth_date_9c = ? AND auth_time_9c = ?")
                        .params(append(SegmentTables.values(new Child(at.acctId(), detail)),
                                at.acctId(), at.key().authDate9c(), at.key().authTime9c()))
                        .update();
            }
        }
        return ended(DliStatus.OK);
    }

    /** {@code DLET SEGMENT(type) FROM(segment)}. A root takes its dependents with it. */
    public DliStatus dlet(Segment segment) {
        Optional<DliStatus> refused = checkHold(segment);
        if (refused.isPresent()) {
            return ended(refused.get());
        }
        switch (position) {
            case Position.Root root -> jdbc.sql("DELETE FROM " + SegmentTables.SUMMARIES + " WHERE acct_id = ?")
                    .param(root.acctId()).update();
            case Position.Dependent at -> jdbc.sql("DELETE FROM " + SegmentTables.DETAILS
                    + " WHERE acct_id = ? AND auth_date_9c = ? AND auth_time_9c = ?")
                    .params(at.acctId(), at.key().authDate9c(), at.key().authTime9c()).update();
            case Position.Start start -> throw new IllegalStateException("a held segment has a position");
        }
        return ended(DliStatus.OK);
    }

    /** {@code ISRT SEGMENT(PAUTSUM0) FROM(root)}. */
    public DliStatus isrt(PendingAuthSummary root) {
        held = Optional.empty();
        int inserted = jdbc.sql(SegmentTables.INSERT_SUMMARY + " ON CONFLICT DO NOTHING")
                .params(SegmentTables.values(root)).update();
        if (inserted == 0) {
            return ended(DliStatus.II);
        }
        position = new Position.Root(root.acctId());
        return ended(DliStatus.OK);
    }

    /** {@code ISRT SEGMENT(PAUTDTL1) FROM(detail)}: under the root of the current position. */
    public DliStatus isrt(PendingAuthDetail detail) {
        return isrt(Ssa.summary(), detail);
    }

    /**
     * {@code ISRT SEGMENT(PAUTSUM0) WHERE (ACCNTID = n) SEGMENT(PAUTDTL1) FROM(detail)}. An unqualified parent
     * SSA means the root of the current position.
     */
    public DliStatus isrt(Ssa.Summary parent, PendingAuthDetail detail) {
        held = Optional.empty();
        OptionalLong acctId = parent.acctId().isPresent() ? parent.acctId() : rootOf(position);
        if (acctId.isEmpty() || firstRoot(OptionalLong.empty(), acctId).isEmpty()) {
            return ended(DliStatus.GE);
        }
        int inserted = jdbc.sql(SegmentTables.INSERT_DETAIL + " ON CONFLICT DO NOTHING")
                .params(SegmentTables.values(new Child(acctId.getAsLong(), detail))).update();
        if (inserted == 0) {
            return ended(DliStatus.II);
        }
        position = new Position.Dependent(acctId.getAsLong(), detail.key());
        return ended(DliStatus.OK);
    }

    private Optional<DliStatus> checkHold(Segment segment) {
        Optional<Segment> was = held;
        held = Optional.empty();
        if (was.isEmpty() || was.get().type() != segment.type()) {
            return Optional.of(DliStatus.DJ);
        }
        boolean sameKey = switch (segment) {
            case PendingAuthSummary root -> root.acctId() == ((PendingAuthSummary) was.get()).acctId();
            case PendingAuthDetail detail -> detail.key().equals(((PendingAuthDetail) was.get()).key());
        };
        return sameKey ? Optional.empty() : Optional.of(DliStatus.DA);
    }

    private Segment reachedRoot(PendingAuthSummary root) {
        position = new Position.Root(root.acctId());
        return root;
    }

    private Segment reachedChild(Child child) {
        position = new Position.Dependent(child.acctId(), child.detail().key());
        return child.detail();
    }

    @SuppressWarnings("unchecked")
    private <S extends Segment> Get<S> getEnded(Optional<Segment> found, boolean setsParentage, DliStatus notFound) {
        if (found.isEmpty()) {
            if (setsParentage) {
                parentage = Optional.empty();
            }
            status = notFound;
            return Get.not(notFound);
        }
        if (setsParentage) {
            parentage = Optional.of(position);
        }
        held = found;
        status = DliStatus.OK;
        return Get.found((S) found.get());
    }

    private DliStatus ended(DliStatus result) {
        status = result;
        return result;
    }

    private static OptionalLong rootOf(Position position) {
        return switch (position) {
            case Position.Start start -> OptionalLong.empty();
            case Position.Root root -> OptionalLong.of(root.acctId());
            case Position.Dependent dependent -> OptionalLong.of(dependent.acctId());
        };
    }

    /** The first root after {@code after} (from the beginning when empty), with ACCNTID {@code equal} if given. */
    private Optional<PendingAuthSummary> firstRoot(OptionalLong after, OptionalLong equal) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE true");
        after.ifPresent(a -> {
            where.append(" AND acct_id > ?");
            params.add(a);
        });
        equal.ifPresent(e -> {
            where.append(" AND acct_id = ?");
            params.add(e);
        });
        return jdbc.sql("SELECT " + SegmentTables.SUMMARY_COLUMNS + " FROM " + SegmentTables.SUMMARIES + where
                        + " ORDER BY acct_id LIMIT 1 FOR UPDATE")
                .params(params).query((row, n) -> SegmentTables.summary(row)).optional();
    }

    /**
     * The first dependent after {@code from} in hierarchical sequence, with key {@code equal} if given, under
     * root {@code within} if given.
     */
    private Optional<Child> firstChild(Position from, Optional<AuthKey> equal, OptionalLong within) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE true");
        switch (from) {
            case Position.Start start -> {
            }
            case Position.Root root -> {
                where.append(" AND acct_id >= ?");
                params.add(root.acctId());
            }
            case Position.Dependent dependent -> {
                where.append(" AND (acct_id, auth_date_9c, auth_time_9c) > (?, ?, ?)");
                params.addAll(List.of(dependent.acctId(), dependent.key().authDate9c(), dependent.key().authTime9c()));
            }
        }
        within.ifPresent(acctId -> {
            where.append(" AND acct_id = ?");
            params.add(acctId);
        });
        equal.ifPresent(key -> {
            where.append(" AND auth_date_9c = ? AND auth_time_9c = ?");
            params.addAll(List.of(key.authDate9c(), key.authTime9c()));
        });
        return jdbc.sql("SELECT " + SegmentTables.DETAIL_COLUMNS + " FROM " + SegmentTables.DETAILS + where
                        + " ORDER BY acct_id, auth_date_9c, auth_time_9c LIMIT 1 FOR UPDATE")
                .params(params).query((row, n) -> SegmentTables.child(row)).optional();
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static Object[] append(Object[] values, Object... more) {
        Object[] all = Arrays.copyOf(values, values.length + more.length);
        System.arraycopy(more, 0, all, values.length, more.length);
        return all;
    }
}
