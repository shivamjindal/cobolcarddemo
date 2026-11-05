package com.carddemo.batch.library;

import static com.carddemo.batch.jcl.Dd.shr;
import static com.carddemo.batch.jcl.Dd.sysout;
import static com.carddemo.batch.jcl.StepDef.program;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.ims.Dfsrrc00;
import com.carddemo.batch.jcl.Cond;
import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.DsnRef;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.programs.Cbact01c;
import com.carddemo.batch.programs.Cbact02c;
import com.carddemo.batch.programs.Cbact03c;
import com.carddemo.batch.programs.Cbact04c;
import com.carddemo.batch.programs.Cbcus01c;
import com.carddemo.batch.programs.Cbpaup0c;
import com.carddemo.batch.programs.Dbunldgs;
import com.carddemo.batch.programs.Paudblod;
import com.carddemo.batch.programs.Paudbunl;
import com.carddemo.batch.programs.Cbstm03a;
import com.carddemo.batch.programs.Cbtrn02c;
import com.carddemo.batch.programs.Iefbr14;
import com.carddemo.batch.programs.Ikjeft01;
import com.carddemo.batch.programs.Cobswait;
import com.carddemo.batch.programs.Sdsf;
import com.carddemo.batch.utilities.Idcams;
import com.carddemo.batch.utilities.Sort;

/**
 * The members of {@code app/jcl}, and of an optional extension's {@code jcl}, that run here, as typed JCL. STEPLIB
 * is left out: programs come from the program library.
 */
public enum LibraryJob {
    READCARD(JobDef.of("READCARD", program("STEP05", Cbact02c.NAME,
            shr("CARDFILE", "AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    READXREF(JobDef.of("READXREF", program("STEP05", Cbact03c.NAME,
            shr("XREFFILE", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    READCUST(JobDef.of("READCUST", program("STEP05", Cbcus01c.NAME,
            shr("CUSTFILE", "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    POSTTRAN(JobDef.of("POSTTRAN", program("STEP15", Cbtrn02c.NAME,
            shr("TRANFILE", "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS"),
            shr("DALYTRAN", "AWS.M2.CARDDEMO.DALYTRAN.PS"),
            shr("XREFFILE", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"),
            generation("DALYREJS", Gdg.DALYREJS, 430),
            shr("ACCTFILE", "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS"),
            shr("TCATBALF", "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS"),
            sysout("SYSOUT"), sysout("SYSPRINT")))),
    INTCALC(JobDef.of("INTCALC", program("STEP15", Cbact04c.NAME,
            shr("TCATBALF", "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS"),
            shr("XREFFILE", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"),
            shr("XREFFIL1", "AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH"),
            shr("ACCTFILE", "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS"),
            shr("DISCGRP", "AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS"),
            generation("TRANSACT", Gdg.SYSTRAN, 350),
            sysout("SYSOUT"), sysout("SYSPRINT")).parm("2022071800"))),
    /**
     * SORTOUT states LRECL 350. {@code DCB=(*.SORTIN)} does not copy the catalog label, and both concatenated
     * inputs are 350-byte transaction records.
     */
    COMBTRAN(JobDef.of("COMBTRAN",
            program("STEP05R", Sort.NAME,
                    new Dd("SORTIN", List.of(
                            relative(Gdg.TRANSACT_BKUP, 0, Disposition.SHR, 0),
                            relative(Gdg.SYSTRAN, 0, Disposition.SHR, 0))),
                    instream("SYMNAMES", "TRAN-ID,1,16,CH"),
                    sysin(" SORT FIELDS=(TRAN-ID,A)"),
                    sysout("SYSOUT"),
                    Dd.of("SORTOUT", relative(Gdg.TRANSACT_COMBINED, 1, newCatalog(), 350))),
            program("STEP10", Idcams.NAME, sysout("SYSPRINT"),
                    Dd.of("TRANSACT", relative(Gdg.TRANSACT_COMBINED, 1, Disposition.SHR, 0)),
                    shr("TRANVSAM", "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS"),
                    sysin("   REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)")))),
    CREASTMT(JobDef.of("CREASTMT",
            program("DELDEF01", Idcams.NAME, sysout("SYSPRINT"), sysin("""
                      DELETE    AWS.M2.CARDDEMO.TRXFL.SEQ
                      DELETE    AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS                     -
                                CLUSTER
                      SET       MAXCC = 0
                      DEFINE    CLUSTER  (NAME(AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS)     -
                                          KEYS(32 0)                                -
                                          VOLUMES(TSU023)                           -
                                          RECORDSIZE(350 350)                       -
                                          SHAREOPTIONS(2 3)                         -
                                          ERASE                                     -
                                          INDEXED                                   -
                                          CYL(1 5))                                 -
                                DATA      (NAME(AWS.M2.CARDDEMO.TRXFL.DATA)         -
                                          CISZ(4096))                               -
                                INDEX     (NAME(AWS.M2.CARDDEMO.TRXFL.INDEX))
                    """)),
            program("STEP010", Sort.NAME,
                    shr("SORTIN", "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS"),
                    sysout("SYSPRINT"), sysout("SYSOUT"),
                    created("SORTOUT", "AWS.M2.CARDDEMO.TRXFL.SEQ", new Dcb(Dcb.Recfm.F, 350)),
                    sysin("""
                              SORT FIELDS=(263,16,CH,A,1,16,CH,A)
                              OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)
                            """)),
            program("STEP020", Idcams.NAME, sysout("SYSPRINT"),
                    shr("INFILE", "AWS.M2.CARDDEMO.TRXFL.SEQ"),
                    shr("OUTFILE", "AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS"),
                    sysin("  REPRO INFILE(INFILE) OUTFILE(OUTFILE)")).cond(cond(0, Cond.Operator.NE)),
            program("STEP030", Iefbr14.NAME,
                    modDelete("HTMLFILE", "AWS.M2.CARDDEMO.STATEMNT.HTML"),
                    modDelete("STMTFILE", "AWS.M2.CARDDEMO.STATEMNT.PS")).cond(cond(0, Cond.Operator.NE)),
            program("STEP040", Cbstm03a.NAME, sysout("SYSPRINT"), sysout("SYSOUT"),
                    shr("TRNXFILE", "AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS"),
                    shr("XREFFILE", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"),
                    shr("ACCTFILE", "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS"),
                    shr("CUSTFILE", "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS"),
                    created("STMTFILE", "AWS.M2.CARDDEMO.STATEMNT.PS", new Dcb(Dcb.Recfm.F, 80)),
                    created("HTMLFILE", "AWS.M2.CARDDEMO.STATEMNT.HTML", new Dcb(Dcb.Recfm.F, 100)))
                    .cond(cond(0, Cond.Operator.NE)))),
    TRANBKP(JobDef.of("TRANBKP",
            program("STEP05R", Idcams.NAME, sysout("SYSPRINT"),
                    shr("FILEIN", "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS"),
                    generation("FILEOUT", Gdg.TRANSACT_BKUP, 350),
                    sysin("    REPRO INFILE(FILEIN) OUTFILE(FILEOUT)")),
            program("STEP05", Idcams.NAME, sysout("SYSPRINT"), sysin("""
                       DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS -
                              CLUSTER
                       IF MAXCC LE 08 THEN SET MAXCC = 0
                       DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX -
                              ALTERNATEINDEX
                       IF MAXCC LE 08 THEN SET MAXCC = 0
                    """)),
            program("STEP10", Idcams.NAME, sysout("SYSPRINT"), sysin("""
                       DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS) -
                              CYLINDERS(1 5) -
                              VOLUMES(AWSHJ1 -
                              ) -
                              KEYS(16 0) -
                              RECORDSIZE(350 350) -
                              SHAREOPTIONS(2 3) -
                              ERASE -
                              INDEXED -
                              ) -
                              DATA (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.DATA) -
                              ) -
                              INDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.INDEX) -
                              )
                    """)).cond(cond(4, Cond.Operator.LT)))),
    READACCT(JobDef.of("READACCT",
            program("PREDEL", Iefbr14.NAME,
                    modDelete("DD01", "AWS.M2.CARDDEMO.ACCTDATA.PSCOMP"),
                    modDelete("DD02", "AWS.M2.CARDDEMO.ACCTDATA.ARRYPS"),
                    modDelete("DD03", "AWS.M2.CARDDEMO.ACCTDATA.VBPS")),
            program("STEP05", Cbact01c.NAME,
                    shr("ACCTFILE", "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS"),
                    created("OUTFILE", "AWS.M2.CARDDEMO.ACCTDATA.PSCOMP", new Dcb(Dcb.Recfm.F, 107)),
                    created("ARRYFILE", "AWS.M2.CARDDEMO.ACCTDATA.ARRYPS", new Dcb(Dcb.Recfm.F, 110)),
                    created("VBRCFILE", "AWS.M2.CARDDEMO.ACCTDATA.VBPS", new Dcb(Dcb.Recfm.V, 84)),
                    sysout("SYSOUT"), sysout("SYSPRINT")))),
    CLOSEFIL(JobDef.of("CLOSEFIL", program("CLCIFIL", Sdsf.NAME, sysout("ISFOUT"), sysout("CMDOUT"), instream("ISFIN",
            " /F CICSAWSA,'CEMT SET FIL(TRANSACT ) CLO'",
            " /F CICSAWSA,'CEMT SET FIL(CCXREF ) CLO'",
            " /F CICSAWSA,'CEMT SET FIL(ACCTDAT ) CLO'",
            " /F CICSAWSA,'CEMT SET FIL(CXACAIX ) CLO'",
            " /F CICSAWSA,'CEMT SET FIL(USRSEC ) CLO'")))),
    OPENFIL(JobDef.of("OPENFIL", program("OPCIFIL", Sdsf.NAME, sysout("ISFOUT"), sysout("CMDOUT"), instream("ISFIN",
            " /F CICSAWSA,'CEMT SET FIL(TRANSACT ) OPE'",
            " /F CICSAWSA,'CEMT SET FIL(CCXREF ) OPE'",
            " /F CICSAWSA,'CEMT SET FIL(ACCTDAT ) OPE'",
            " /F CICSAWSA,'CEMT SET FIL(CXACAIX ) OPE'",
            " /F CICSAWSA,'CEMT SET FIL(USRSEC ) OPE'")))),
    WAITSTEP(JobDef.of("WAITSTEP", program("WAIT", Cobswait.NAME, sysout("SYSOUT"),
            instream("SYSIN", "00003600      VALUE IN CENTISECONDS")))),
    /** {@code app/app-transaction-type-db2/jcl/MNTTRDB2.jcl}. DBRMLIB is left out too: RUN does not bind. */
    MNTTRDB2(JobDef.of("MNTTRDB2", program("STEP1", Ikjeft01.NAME,
            sysout("SYSTSPRT"), shr("INPFILE", "INPFILE"),
            instream("SYSTSIN",
                    "     DSN SYSTEM(DAZ1)",
                    "          RUN PROGRAM(COBTUPDT) PLAN(CARDDEMO)")))),
    /**
     * The IMS jobs of app-authorization-ims-db2-mq start here. Their IMS system DDs (DFSRESLB, PROCLIB, DFSSEL,
     * IMS, DFSVSAMP, DDPAUTP0, DDPAUTX0, IEFRDER, IMSLOGR) are left out with STEPLIB: DFSRRC00 is a bean and
     * DBPAUTP0 is V20's tables.
     */
    CBPAUP0J(JobDef.of("CBPAUP0J", program("STEP01", Dfsrrc00.NAME,
            instream("SYSIN", "00,00001,00001,Y"),
            sysout("SYSOUX"), sysout("SYSOUT"), sysout("SYSABOUT"), sysout("ABENDAID"), sysout("SYSPRINT"),
            sysout("SYSUDUMP"), sysout("IMSERR")).parm("BMP," + Cbpaup0c.NAME + "," + Cbpaup0c.PSB))),
    /** STEP0 deletes the last unload, and DISP=OLD fails the job with a JCL error when there is none. */
    UNLDPADB(JobDef.of("UNLDPADB",
            program("STEP0", Iefbr14.NAME, sysout("SYSPRINT"), sysout("SYSOUT"), sysout("SYSDUMP"),
                    dataset("DD1", "AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO", oldThen(Disposition.End.DELETE), Optional.empty()),
                    dataset("DD2", "AWS.M2.CARDDEMO.PAUTDB.CHILD.FILEO", oldThen(Disposition.End.DELETE), Optional.empty())),
            program("STEP01", Dfsrrc00.NAME,
                    created("OUTFIL1", "AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO", new Dcb(Dcb.Recfm.F, 100)),
                    created("OUTFIL2", "AWS.M2.CARDDEMO.PAUTDB.CHILD.FILEO", new Dcb(Dcb.Recfm.F, 206)),
                    sysout("SYSPRINT"), sysout("SYSUDUMP"), sysout("IMSERR"))
                    .parm("DLI," + Paudbunl.NAME + "," + Paudbunl.PSB + ",,,,,,,,,,,N"))),
    LOADPADB(JobDef.of("LOADPADB", program("STEP01", Dfsrrc00.NAME,
            shr("INFILE1", "AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO"), shr("INFILE2", "AWS.M2.CARDDEMO.PAUTDB.CHILD.FILEO"),
            sysout("SYSPRINT"), sysout("SYSUDUMP"), sysout("IMSERR")).parm("BMP," + Paudblod.NAME + "," + Paudblod.PSB))),
    /** The GSAM data sets are DISP=OLD, so they must be cataloged before the first run. */
    UNLDGSAM(JobDef.of("UNLDGSAM", program("STEP01", Dfsrrc00.NAME,
            dataset("PASFILOP", "AWS.M2.CARDDEMO.PAUTDB.ROOT.GSAM", oldThen(Disposition.End.KEEP), Optional.empty()),
            dataset("PADFILOP", "AWS.M2.CARDDEMO.PAUTDB.CHILD.GSAM", oldThen(Disposition.End.KEEP), Optional.empty()),
            sysout("SYSPRINT"), sysout("SYSUDUMP"), sysout("IMSERR"))
            .parm("DLI," + Dbunldgs.NAME + "," + Dbunldgs.PSB + ",,,,,,,,,,,N")));

    private final JobDef job;

    LibraryJob(JobDef job) {
        this.job = job;
    }

    public JobDef job() {
        return job;
    }

    public static Optional<LibraryJob> byName(String name) {
        return Arrays.stream(values()).filter(j -> j.name().equals(name)).findFirst();
    }

    private static Dd generation(String name, Gdg gdg, int lrecl) {
        return Dd.of(name, relative(gdg, 1, newCatalog(), lrecl));
    }

    /** An LRECL of 0 leaves the catalog label's DCB in place. */
    private static DdSource relative(Gdg gdg, int relative, Disposition disposition, int lrecl) {
        return new DdSource.Dataset(new DsnRef.Generation(gdg, relative), disposition,
                lrecl == 0 ? Optional.empty() : Optional.of(new Dcb(Dcb.Recfm.F, lrecl)));
    }

    private static Disposition newCatalog() {
        return Disposition.of(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.DELETE);
    }

    /**
     * {@code COND=(code,op)} against every earlier step. The test is {@code code op rc}, so {@code (0,NE)} holds
     * when the return code is not 0 and {@code (4,LT)} holds when it is above 4.
     */
    private static Cond cond(int code, Cond.Operator operator) {
        return new Cond(List.of(new Cond.Test(code, operator, Optional.empty())));
    }

    private static Dd modDelete(String name, String dsn) {
        return dataset(name, dsn, Disposition.of(Disposition.Status.MOD, Disposition.End.DELETE, Disposition.End.DELETE),
                Optional.empty());
    }

    private static Disposition oldThen(Disposition.End end) {
        return Disposition.of(Disposition.Status.OLD, end, end);
    }

    private static Dd created(String name, String dsn, Dcb dcb) {
        return dataset(name, dsn, Disposition.of(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.DELETE),
                Optional.of(dcb));
    }

    private static Dd dataset(String name, String dsn, Disposition disposition, Optional<Dcb> dcb) {
        return Dd.of(name, new DdSource.Dataset(new DsnRef.Named(dsn), disposition, dcb));
    }

    private static Dd instream(String dd, String... cards) {
        return Dd.of(dd, new DdSource.Instream(List.of(cards)));
    }

    private static Dd sysin(String block) {
        return instream("SYSIN", block.strip().split("\\R"));
    }
}
