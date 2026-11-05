package com.carddemo.batch.runtime;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Job output by job execution, step path and DD: DISPLAY lines under {@code SYSOUT}, and every SYSOUT=* DD. */
@Component
public final class Spool {

    /** The DD DISPLAY writes to. */
    public static final String DISPLAY = "SYSOUT";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    Spool(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public List<byte[]> read(long jobExecutionId, String step, String dd) {
        return jdbc.sql("select record from spool where job_execution_id = ? and step = ? and dd = ? order by seq")
                .params(jobExecutionId, step, dd).query((rs, row) -> rs.getBytes(1)).list();
    }

    void append(long jobExecutionId, String step, String dd, List<byte[]> records) {
        int next = jdbc.sql("select coalesce(max(seq), 0) + 1 from spool where job_execution_id = ? and step = ? and dd = ?")
                .params(jobExecutionId, step, dd).query(Integer.class).single();
        List<Object[]> rows = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            rows.add(new Object[] {jobExecutionId, step, dd, next + i, records.get(i)});
        }
        template.batchUpdate("insert into spool (job_execution_id, step, dd, seq, record) values (?, ?, ?, ?, ?)", rows);
    }
}
