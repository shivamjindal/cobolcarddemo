package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.Step;
import com.carddemo.cics.CicsFile;
import com.carddemo.cics.CicsRegion;

/**
 * SDSF in batch, for the ISFIN cards of CLOSEFIL and OPENFIL: each {@code /F CICSAWSA,'CEMT SET FIL(name) CLO'} or
 * {@code OPE} is applied to the region in card order. A close returns once the online tasks using the file have
 * ended, so the job's next step starts with the file drained. A card the region cannot apply changes nothing and
 * the step ends RC 8.
 */
@Component
public final class Sdsf implements BatchProgram {

    public static final String NAME = "SDSF";

    private static final Logger log = LoggerFactory.getLogger(Sdsf.class);
    private static final Pattern CEMT_SET_FILE = Pattern.compile("/F (\\w+),'CEMT SET FIL\\((\\w+) *\\) (CLO|OPE)'");
    private static final int NOT_APPLIED = 8;

    private final CicsRegion region;

    public Sdsf(CicsRegion region) {
        this.region = region;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        SequentialInput isfin = step.input("ISFIN");
        if (isfin.open() != FileStatus.OK) {
            log.warn("SDSF has no ISFIN");
            return NOT_APPLIED;
        }
        int rc = 0;
        for (Read read = isfin.read(); read instanceof Read.Found found; read = isfin.read()) {
            String card = new String(found.record(), ISO_8859_1).strip();
            if (!apply(card)) {
                log.warn("ISFIN card not applied: {}", card);
                rc = NOT_APPLIED;
            }
        }
        isfin.close();
        return rc;
    }

    private boolean apply(String card) {
        Matcher command = CEMT_SET_FILE.matcher(card);
        if (!command.matches() || !command.group(1).equals(CicsRegion.APPLID)) {
            return false;
        }
        Optional<CicsFile> file = Arrays.stream(CicsFile.values()).filter(f -> f.name().equals(command.group(2)))
                .findFirst();
        if (file.isEmpty()) {
            return false;
        }
        if (command.group(3).equals("CLO")) {
            region.close(file.get());
        } else {
            region.open(file.get());
        }
        return true;
    }
}
