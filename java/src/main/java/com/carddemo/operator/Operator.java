package com.carddemo.operator;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Spool;
import com.carddemo.jobs.JobQueue;

/**
 * The operator commands that read a finished submission or a cataloged data set. {@code cemt}, {@code start}
 * and {@code restart} are further commands on {@link #execute}.
 */
@Component
public final class Operator {

    private static final Pattern GENERATION = Pattern.compile("(.+)\\(([+-]?\\d+)\\)");

    private final JobQueue queue;
    private final DatasetStore datasets;
    private final Spool spool;
    private final JdbcClient jdbc;

    public Operator(JobQueue queue, DatasetStore datasets, Spool spool, JdbcClient jdbc) {
        this.queue = queue;
        this.datasets = datasets;
        this.spool = spool;
        this.jdbc = jdbc;
    }

    /** Runs one command. The process exit code is the return value. */
    public int execute(List<String> args) {
        if (args.isEmpty()) {
            System.err.println("usage: output <submissionId> | print <dsn>");
            return 8;
        }
        return switch (args.getFirst()) {
            case "output" -> output(args.subList(1, args.size()));
            case "print" -> print(args.subList(1, args.size()));
            default -> {
                System.err.println("unknown command " + args.getFirst());
                yield 8;
            }
        };
    }

    private int output(List<String> args) {
        if (args.size() != 1) {
            System.err.println("usage: output <submissionId>");
            return 8;
        }
        long submission;
        try {
            submission = Long.parseLong(args.getFirst());
        } catch (NumberFormatException e) {
            System.err.println("submission id is not a number");
            return 8;
        }
        Optional<JobQueue.Run> claim = queue.run(submission);
        if (claim.isEmpty()) {
            System.err.println("submission " + submission + " was not claimed");
            return 4;
        }
        JobQueue.Run run = claim.get();
        System.out.println("submission " + submission + " " + run.completion().orElse("RUNNING"));
        if (run.jobExecutionId().isEmpty()) {
            return 0;
        }
        long execution = run.jobExecutionId().getAsLong();
        jdbc.sql("select step_name, exit_code from batch_step_execution where job_execution_id = ?"
                        + " order by step_execution_id")
                .param(execution)
                .query((rs, row) -> rs.getString(1) + " " + rs.getString(2))
                .list()
                .forEach(System.out::println);
        List<String[]> groups = jdbc.sql("select distinct step, dd from spool where job_execution_id = ? order by 1, 2")
                .param(execution)
                .query((rs, row) -> new String[] {rs.getString(1), rs.getString(2)})
                .list();
        for (String[] group : groups) {
            System.out.println(group[0] + " " + group[1]);
            for (byte[] record : spool.read(execution, group[0], group[1])) {
                System.out.println(new String(record, ISO_8859_1));
            }
        }
        return 0;
    }

    private int print(List<String> args) {
        if (args.size() != 1) {
            System.err.println("usage: print <dsn>");
            return 8;
        }
        String name = args.getFirst();
        Optional<List<byte[]>> records = read(name);
        if (records.isEmpty()) {
            System.err.println("DATA SET " + name + " NOT FOUND");
            return 4;
        }
        for (byte[] record : records.get()) {
            System.out.println(new String(record, ISO_8859_1));
        }
        return 0;
    }

    private Optional<List<byte[]>> read(String name) {
        Matcher generation = GENERATION.matcher(name);
        if (generation.matches()) {
            return datasets.generation(generation.group(1), Integer.parseInt(generation.group(2)));
        }
        return datasets.readNamed(name);
    }
}
