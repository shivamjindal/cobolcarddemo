package com.carddemo.operator;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.carddemo.batch.chains.Chain;
import com.carddemo.batch.chains.ChainRun;
import com.carddemo.batch.chains.ChainRunner;
import com.carddemo.batch.chains.Chains;
import com.carddemo.batch.runtime.Completion;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Spool;
import com.carddemo.cics.CicsFile;
import com.carddemo.cics.CicsRegion;
import com.carddemo.jobs.JobQueue;

/**
 * Operator commands. {@code output} and {@code print} read a finished submission or a cataloged data set.
 * {@code cemt}, {@code start}, {@code restart}, {@code abandon} and {@code chains} drive the region and the
 * scheduler.
 */
@Component
public final class Operator {

    private static final Pattern GENERATION = Pattern.compile("(.+)\\(([+-]?\\d+)\\)");
    private static final String USAGE = "usage: output <submissionId> | print <dsn> | cemt open|close <file>"
            + " | start <chain> [orderDate] | restart <chain> <orderDate> | abandon <chain> <orderDate> | chains";

    private final JobQueue queue;
    private final DatasetStore datasets;
    private final Spool spool;
    private final JdbcClient jdbc;
    private final CicsRegion region;
    private final Chains chains;
    private final ChainRunner runner;
    private final Clock clock;

    public Operator(JobQueue queue, DatasetStore datasets, Spool spool, JdbcClient jdbc, CicsRegion region,
            Chains chains, ChainRunner runner, Clock clock) {
        this.queue = queue;
        this.datasets = datasets;
        this.spool = spool;
        this.jdbc = jdbc;
        this.region = region;
        this.chains = chains;
        this.runner = runner;
        this.clock = clock;
    }

    /** Runs one command. The process exit code is the return value. */
    public int execute(List<String> args) {
        if (args.isEmpty()) {
            System.err.println(USAGE);
            return 8;
        }
        return switch (args.getFirst()) {
            case "output" -> output(args.subList(1, args.size()));
            case "print" -> print(args.subList(1, args.size()));
            case "cemt" -> cemt(args.subList(1, args.size()));
            case "start" -> chain(args.subList(1, args.size()), false, true);
            case "restart" -> chain(args.subList(1, args.size()), true, false);
            case "abandon" -> chain(args.subList(1, args.size()), false, false);
            case "chains" -> list(args.subList(1, args.size()));
            default -> {
                System.err.println("unknown command " + args.getFirst());
                yield 8;
            }
        };
    }

    private int cemt(List<String> args) {
        if (args.size() != 2 || !(args.getFirst().equals("open") || args.getFirst().equals("close"))) {
            System.err.println("usage: cemt open|close <file>");
            return 8;
        }
        CicsFile file;
        try {
            file = CicsFile.valueOf(args.get(1));
        } catch (IllegalArgumentException e) {
            System.err.println("unknown file " + args.get(1));
            return 8;
        }
        boolean close = args.getFirst().equals("close");
        if (close) {
            region.close(file);
        } else {
            region.open(file);
        }
        System.out.println(file.name() + (close ? " CLOSED" : " OPEN"));
        return 0;
    }

    private int list(List<String> args) {
        if (!args.isEmpty()) {
            System.err.println("usage: chains");
            return 8;
        }
        for (Chain chain : chains.all()) {
            if (chain.runnable()) {
                System.out.println(chain.name() + " runnable. " + chain.exportCalendar());
            } else {
                String why = chain.unported().stream()
                        .map(member -> member.name() + " (" + member.reason() + ")")
                        .collect(Collectors.joining(", "));
                System.out.println(chain.name() + " cannot run: " + why);
            }
        }
        return 0;
    }

    /** {@code start} takes an optional order date, which defaults to today. {@code restart} and {@code abandon} require it. */
    private int chain(List<String> args, boolean restart, boolean dateOptional) {
        String verb = restart ? "restart" : dateOptional ? "start" : "abandon";
        boolean sized = dateOptional ? args.size() == 1 || args.size() == 2 : args.size() == 2;
        if (!sized) {
            System.err.println("usage: " + verb + " <chain>" + (dateOptional ? " [orderDate]" : " <orderDate>"));
            return 8;
        }
        Optional<Chain> chain = chains.byName(args.getFirst());
        if (chain.isEmpty()) {
            System.err.println("chain " + args.getFirst() + " is not defined");
            return 4;
        }
        LocalDate orderDate;
        try {
            orderDate = args.size() == 1 ? LocalDate.now(clock) : LocalDate.parse(args.get(1));
        } catch (DateTimeParseException e) {
            System.err.println("order date is not yyyy-mm-dd");
            return 8;
        }
        try {
            ChainRun run = restart ? runner.restart(chain.get(), orderDate)
                    : dateOptional ? runner.start(chain.get(), orderDate)
                    : runner.abandon(chain.get(), orderDate);
            show(run);
            if (verb.equals("abandon")) {
                return 0;
            }
            return run.completed() ? 0 : 4;
        } catch (ChainRunner.Refused refused) {
            System.err.println(refused.getMessage());
            return 4;
        }
    }

    private static void show(ChainRun run) {
        System.out.println(run.chain() + " " + run.orderDate() + (run.completed() ? " COMPLETED" : " FAILED"));
        for (ChainRun.MemberRun member : run.members()) {
            System.out.println(member.member() + " " + member.completion().map(Completion::label).orElse("NOT RUN"));
        }
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
