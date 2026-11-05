package com.carddemo.report;

import java.util.List;

/** The JOB-DATA lines CORPT00C writes to TD queue JOBS, trailing spaces removed, including the EOF card. */
public final class ReportJcl {

    private ReportJcl() {}

    public static List<String> cards(String start, String end) {
        return List.of(
                "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,",
                "// NOTIFY=&SYSUID",
                "//*",
                "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')",
                "//*",
                "//STEP10 EXEC PROC=TRANREPT",
                "//*",
                "//STEP05R.SYMNAMES DD *",
                "TRAN-CARD-NUM,263,16,ZD",
                "TRAN-PROC-DT,305,10,CH",
                "PARM-START-DATE,C'" + start + "'",
                "PARM-END-DATE,C'" + end + "'",
                "/*",
                "//STEP10R.DATEPARM DD *",
                start + " " + end,
                "/*",
                "/*EOF").stream().map(String::stripTrailing).toList();
    }
}
