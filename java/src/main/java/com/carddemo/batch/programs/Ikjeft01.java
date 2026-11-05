package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.carddemo.batch.step.Abend;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.Step;

/**
 * The TSO terminal monitor program in batch, for the one SYSTSIN its jobs code: {@code DSN SYSTEM(ssid)} then
 * {@code RUN PROGRAM(name) PLAN(plan)}. RUN calls the named program on this step, so it sees the step's DDs, and
 * the step ends with that program's return code. Any other command ends the step RC 12.
 */
@Component
public final class Ikjeft01 implements BatchProgram {

    public static final String NAME = "IKJEFT01";

    private static final Logger log = LoggerFactory.getLogger(Ikjeft01.class);
    private static final Pattern DSN = Pattern.compile("DSN\\s+SYSTEM\\(\\w+\\)");
    private static final Pattern RUN = Pattern.compile("RUN\\s+PROGRAM\\((\\w+)\\)\\s+PLAN\\((\\w+)\\)");
    private static final int COMMAND_FAILED = 12;

    private final ObjectProvider<BatchProgram> programs;

    public Ikjeft01(ObjectProvider<BatchProgram> programs) {
        this.programs = programs;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        SequentialInput systsin = step.input("SYSTSIN");
        if (systsin.open() != FileStatus.OK) {
            log.warn("IKJEFT01 has no SYSTSIN");
            return COMMAND_FAILED;
        }
        boolean underDsn = false;
        int rc = 0;
        for (Read read = systsin.read(); read instanceof Read.Found found; read = systsin.read()) {
            String command = new String(found.record(), ISO_8859_1).strip();
            Matcher run = RUN.matcher(command);
            if (DSN.matcher(command).matches()) {
                underDsn = true;
            } else if (underDsn && run.matches()) {
                rc = program(run.group(1)).run(step);
            } else if (!command.isEmpty()) {
                log.warn("SYSTSIN command not supported: {}", command);
                rc = COMMAND_FAILED;
            }
        }
        systsin.close();
        return rc;
    }

    private BatchProgram program(String name) {
        Optional<BatchProgram> found = programs.stream().filter(p -> p.name().equals(name)).findFirst();
        return found.orElseThrow(() -> Abend.system("S806"));
    }
}
