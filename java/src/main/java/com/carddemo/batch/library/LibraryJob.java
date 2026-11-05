package com.carddemo.batch.library;

import static com.carddemo.batch.jcl.Dd.shr;
import static com.carddemo.batch.jcl.Dd.sysout;
import static com.carddemo.batch.jcl.StepDef.program;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
import com.carddemo.batch.programs.Cbstm03a;
import com.carddemo.batch.programs.Cbtrn02c;
import com.carddemo.batch.programs.Iefbr14;
import com.carddemo.batch.programs.Cobswait;
import com.carddemo.batch.programs.Sdsf;
import com.carddemo.batch.utilities.Idcams;
import com.carddemo.batch.utilities.Sort;

/** The members of {@code app/jcl} that run here, as typed JCL. STEPLIB is left out: programs come from the program library. */
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
    TRANTYPE(reload("TRANTYPE", "TRANTYPE", "TTYPVSAM", "2 0", "60 60", "1 4")),
    TRANCATG(reload("TRANCATG", "TRANCATG", "TCATVSAM", "6 0", "60 60", "2 3")),
    TCATBALF(reload("TCATBALF", "TCATBAL", "TCATBALV", "17 0", "50 50", "2 3")),
    DISCGRP(reload("DISCGRP", "DISCGRP", "DISCVSAM", "16 0", "50 50", "2 3")),
    /**
     * {@code EXEC PROC=REPROC,CNTLLIB=} expands {@link Proc#REPROC}. The step's SYSIN is that procedure's REPRO
     * card, the text of {@code REPROCT}, rather than {@code DSN=&CNTLLIB(REPROCT)}. UNIT, SPACE and BLKSIZE are
     * not in the parsed job.
     */
    PRTCATBL(JobDef.of("PRTCATBL",
            program("DELDEF", Iefbr14.NAME, modDelete("THEFILE", "AWS.M2.CARDDEMO.TCATBALF.REPT")),
            program("STEP05R.PRC001", Idcams.NAME, sysout("SYSPRINT"),
                    shr("FILEIN", "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS"),
                    Dd.of("FILEOUT", relative(Gdg.TCATBALF_BKUP, 1, newCatalog(), 50)),
                    instream("SYSIN", "    REPRO INFILE(FILEIN) OUTFILE(FILEOUT)")),
            program("STEP10R", Sort.NAME,
                    Dd.of("SORTIN", relative(Gdg.TCATBALF_BKUP, 1, Disposition.SHR, 0)),
                    instream("SYMNAMES",
                            "TRANCAT-ACCT-ID,1,11,ZD",
                            "TRANCAT-TYPE-CD,12,2,CH",
                            "TRANCAT-CD,14,4,ZD",
                            "TRAN-CAT-BAL,18,11,ZD"),
                    instream("SYSIN",
                            " SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)",
                            " OUTREC FIELDS=(TRANCAT-ACCT-ID,X,",
                            "     TRANCAT-TYPE-CD,X,",
                            "     TRANCAT-CD,X,",
                            "     TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)"),
                    sysout("SYSOUT"),
                    created("SORTOUT", "AWS.M2.CARDDEMO.TCATBALF.REPT", new Dcb(Dcb.Recfm.F, 40))))),
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
            instream("SYSIN", "00003600      VALUE IN CENTISECONDS"))));

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

    /**
     * DELETE, DEFINE and REPRO of one reference cluster. The input DD is the sequential file and the output DD is
     * the cluster, opened OLD.
     */
    private static JobDef reload(String member, String inDd, String outDd, String keys, String recordSize, String share) {
        String ksds = "AWS.M2.CARDDEMO." + member + ".VSAM.KSDS";
        return JobDef.of(member,
                program("STEP05", Idcams.NAME, sysout("SYSPRINT"), instream("SYSIN",
                        "   DELETE " + ksds + " -",
                        "          CLUSTER",
                        "   SET    MAXCC = 0")),
                program("STEP10", Idcams.NAME, sysout("SYSPRINT"), instream("SYSIN",
                        "   DEFINE CLUSTER (NAME(" + ksds + ") -",
                        "          CYLINDERS(1 5) -",
                        "          VOLUMES(AWSHJ1 -",
                        "          ) -",
                        "          KEYS(" + keys + ") -",
                        "          RECORDSIZE(" + recordSize + ") -",
                        "          SHAREOPTIONS(" + share + ") -",
                        "          ERASE -",
                        "          INDEXED -",
                        "          ) -",
                        "          DATA (NAME(" + ksds + ".DATA) -",
                        "          ) -",
                        "          INDEX (NAME(" + ksds + ".INDEX) -",
                        "          )")),
                program("STEP15", Idcams.NAME, sysout("SYSPRINT"),
                        shr(inDd, "AWS.M2.CARDDEMO." + member + ".PS"),
                        dataset(outDd, ksds, Disposition.of(Disposition.Status.OLD, null, null), Optional.empty()),
                        instream("SYSIN", "   REPRO INFILE(" + inDd + ") OUTFILE(" + outDd + ")")));
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
