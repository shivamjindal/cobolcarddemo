package com.carddemo.batch.ims;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.carddemo.batch.step.Abend;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.Step;
import com.carddemo.dli.Dbpautp0;
import com.carddemo.dli.Pcb;

/**
 * DFSRRC00, the IMS region controller, as the IMS jobs code it: {@code PARM='BMP,CBPAUP0C,PSBPAUTB'} or
 * {@code PARM='DLI,PAUDBUNL,PAUTBUNL,,,,,,,,,,,N'}. It schedules the PSB on DBPAUTP0 and calls the program. The
 * program's DL/I changes commit at each CHKP and when it returns, whatever its return code, and back out to the
 * last checkpoint when it abends.
 */
@Component
public final class Dfsrrc00 implements BatchProgram {

    public static final String NAME = "DFSRRC00";

    private static final Set<String> REGION_TYPES = Set.of("BMP", "DLI", "DBB");
    private static final TransactionDefinition UNIT_OF_WORK =
            new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    private final Map<String, DliProgram> programs;
    private final Dbpautp0 database;
    private final PlatformTransactionManager transactions;

    Dfsrrc00(List<DliProgram> programs, Dbpautp0 database, PlatformTransactionManager transactions) {
        this.programs = programs.stream().collect(Collectors.toMap(DliProgram::name, Function.identity()));
        this.database = database;
        this.transactions = transactions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        String[] parm = step.parm().split(",", -1);
        if (parm.length < 3 || !REGION_TYPES.contains(parm[0])) {
            throw new IllegalArgumentException("DFSRRC00 PARM needs region type, program and PSB: " + step.parm());
        }
        DliProgram program = programs.get(parm[1]);
        if (program == null) {
            throw Abend.system("S806");
        }
        if (!program.psb().equals(parm[2])) {
            throw new IllegalArgumentException(program.name() + " is written for PSB " + program.psb() + ", not " + parm[2]);
        }
        Region region = new Region(database.schedule());
        try {
            int returnCode = program.run(step, region);
            transactions.commit(region.unitOfWork);
            return returnCode;
        } catch (RuntimeException e) {
            transactions.rollback(region.unitOfWork);
            throw e;
        }
    }

    private final class Region implements ImsRegion {

        private final Pcb database;
        private TransactionStatus unitOfWork = transactions.getTransaction(UNIT_OF_WORK);

        Region(Pcb database) {
            this.database = database;
        }

        @Override
        public Pcb database() {
            return database;
        }

        @Override
        public void checkpoint() {
            transactions.commit(unitOfWork);
            unitOfWork = transactions.getTransaction(UNIT_OF_WORK);
        }
    }
}
