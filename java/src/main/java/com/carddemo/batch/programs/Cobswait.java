package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.carddemo.batch.step.Abend;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.Step;

/** COBSWAIT: ACCEPTs eight digits of centiseconds from SYSIN and waits that long through MVSWAIT, then STOP RUN. */
@Component
public final class Cobswait implements BatchProgram {

    public static final String NAME = "COBSWAIT";

    /** How the wait passes. Without a {@code Sleeper} bean the step really sleeps. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** What PACK and CVB raise when MOVE PARM-VALUE TO MVSWAIT-TIME meets a non-numeric card. */
    private static final String DATA_EXCEPTION = "S0C7";

    /** The job was cancelled while it waited. */
    private static final String CANCELLED = "S222";

    private final Sleeper sleeper;

    public Cobswait(ObjectProvider<Sleeper> sleeper) {
        this.sleeper = sleeper.getIfAvailable(() -> Thread::sleep);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        String value = parmValue(step.input("SYSIN"));
        if (value.length() != 8 || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw Abend.system(DATA_EXCEPTION);
        }
        try {
            sleeper.sleep(Duration.ofMillis(Long.parseLong(value) * 10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Abend.system(CANCELLED);
        }
        return 0;
    }

    /** PARM-VALUE, the first eight bytes of the first card; empty when SYSIN has no card. */
    private static String parmValue(SequentialInput sysin) {
        if (sysin.open() != FileStatus.OK) {
            return "";
        }
        Read card = sysin.read();
        sysin.close();
        return card instanceof Read.Found found ? new String(found.record(), 0, 8, ISO_8859_1) : "";
    }
}
