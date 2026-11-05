package com.carddemo.batch.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.vsam.KeyRange;

/**
 * The {@code datasets} and {@code dataset_records} tables: sequential data sets, GDG generations and keyed work
 * files. Staging a feed drop and reading what a job cataloged are public; the allocation lifecycle the catalog
 * drives at step end is not.
 */
@Component
public final class DatasetStore {

    record Dataset(long id, String dsn, boolean pending, Dcb dcb, Optional<KeyRange> key) {}

    private static final String COLUMNS = "id, dsn, state, recfm, lrecl, key_offset, key_length";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DatasetStore(JdbcClient jdbc, JdbcTemplate template, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Catalogs {@code dsn} with these records, replacing the data set cataloged under that name. */
    public void stage(String dsn, Dcb dcb, Optional<KeyRange> key, List<byte[]> records) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("delete from datasets where dsn = ? and state = 'CATALOGED' and generation is null")
                    .param(dsn).update();
            long id = insert(dsn, "CATALOGED", dcb, key, null);
            insertRecords(id, 1, records);
        });
    }

    /** The records of the data set cataloged under {@code dsn}, in write order. */
    public Optional<List<byte[]>> read(String dsn) {
        return cataloged(dsn).map(dataset -> records(dataset.id()));
    }

    Optional<Dataset> cataloged(String dsn) {
        return jdbc.sql("select " + COLUMNS + " from datasets where dsn = ? and state = 'CATALOGED'"
                        + " and generation is null")
                .param(dsn).query(DatasetStore::dataset).optional();
    }

    Dataset byId(long id) {
        return jdbc.sql("select " + COLUMNS + " from datasets where id = ?").param(id).query(DatasetStore::dataset)
                .single();
    }

    /** The cataloged generations of every GDG, newest first. */
    Map<Gdg, List<Long>> generations() {
        Map<Gdg, List<Long>> generations = new EnumMap<>(Gdg.class);
        for (Gdg gdg : Gdg.values()) {
            generations.put(gdg, new ArrayList<>());
        }
        jdbc.sql("select dsn, id from datasets where state = 'CATALOGED' and generation is not null"
                        + " order by generation desc")
                .query(rs -> {
                    long id = rs.getLong(2);
                    Gdg.byBase(rs.getString(1)).ifPresent(gdg -> generations.get(gdg).add(id));
                });
        return generations;
    }

    Dataset create(String dsn, Dcb dcb, long jobExecutionId) {
        return byId(insert(dsn, "PENDING", dcb, Optional.empty(), jobExecutionId));
    }

    List<byte[]> records(long id) {
        return jdbc.sql("select record from dataset_records where dataset_id = ? order by seq").param(id)
                .query((rs, row) -> rs.getBytes(1)).list();
    }

    void replace(long id, List<byte[]> records) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("delete from dataset_records where dataset_id = ?").param(id).update();
            insertRecords(id, 1, records);
        });
    }

    void append(long id, List<byte[]> records) {
        transactions.executeWithoutResult(status -> {
            int next = jdbc.sql("select coalesce(max(seq), 0) + 1 from dataset_records where dataset_id = ?")
                    .param(id).query(Integer.class).single();
            insertRecords(id, next, records);
        });
    }

    void catalog(long id) {
        jdbc.sql("update datasets set state = 'CATALOGED' where id = ?").param(id).update();
    }

    /**
     * Catalogs a pending generation as the base's next absolute generation, then rolls the oldest past the
     * limit off: SCRATCH deletes them, NOSCRATCH keeps them uncataloged.
     */
    void catalogGeneration(long id, Gdg gdg) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("select pg_advisory_xact_lock(hashtext(?))").param(gdg.base()).query(rs -> {});
            jdbc.sql("update datasets set state = 'CATALOGED', generation = (select coalesce(max(generation), 0) + 1"
                            + " from datasets where dsn = ? and generation is not null) where id = ?")
                    .params(gdg.base(), id).update();
            String rolledOff = "select id from datasets where dsn = ? and state = 'CATALOGED' and generation is not null"
                    + " order by generation desc offset ?";
            jdbc.sql(gdg.scratch()
                            ? "delete from datasets where id in (" + rolledOff + ")"
                            : "update datasets set state = 'UNCATALOGED' where id in (" + rolledOff + ")")
                    .params(gdg.base(), gdg.limit()).update();
        });
    }

    void uncatalog(long id) {
        jdbc.sql("update datasets set state = 'UNCATALOGED' where id = ?").param(id).update();
    }

    void delete(long id) {
        jdbc.sql("delete from datasets where id = ?").param(id).update();
    }

    /** Deletes what a job passed and never cataloged, as the end of the job does. */
    void deletePending(long jobExecutionId) {
        jdbc.sql("delete from datasets where job_execution_id = ? and state = 'PENDING'").param(jobExecutionId)
                .update();
    }

    private long insert(String dsn, String state, Dcb dcb, Optional<KeyRange> key, Long jobExecutionId) {
        return jdbc.sql("insert into datasets (dsn, state, recfm, lrecl, key_offset, key_length, job_execution_id,"
                        + " created_at) values (?, ?, ?, ?, ?, ?, ?, ?) returning id")
                .params(dsn, state, dcb.recfm().name(), dcb.lrecl() == 0 ? null : dcb.lrecl(),
                        key.map(KeyRange::offset).orElse(null), key.map(KeyRange::length).orElse(null),
                        jobExecutionId, LocalDateTime.now(clock))
                .query(Long.class).single();
    }

    private void insertRecords(long id, int firstSeq, List<byte[]> records) {
        List<Object[]> rows = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            rows.add(new Object[] {id, firstSeq + i, records.get(i)});
        }
        template.batchUpdate("insert into dataset_records (dataset_id, seq, record) values (?, ?, ?)", rows);
    }

    private static Dataset dataset(ResultSet rs, int row) throws SQLException {
        int keyLength = rs.getInt("key_length");
        Optional<KeyRange> key = rs.wasNull() ? Optional.empty() : Optional.of(new KeyRange(rs.getInt("key_offset"), keyLength));
        return new Dataset(rs.getLong("id"), rs.getString("dsn"), rs.getString("state").equals("PENDING"),
                new Dcb(Dcb.Recfm.valueOf(rs.getString("recfm")), rs.getInt("lrecl")), key);
    }
}
