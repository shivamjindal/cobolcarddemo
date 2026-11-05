package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.batch.ims.DliProgram;
import com.carddemo.batch.ims.ImsRegion;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.Step;
import com.carddemo.dli.DliStatus;
import com.carddemo.dli.Get;
import com.carddemo.dli.Pcb;
import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;
import com.carddemo.dli.Ssa;

/**
 * CBPAUP0C: deletes the pending authorizations that are at least the SYSIN card's expiry days old, and the
 * summaries left with no approved authorization, taking a CHKP every so many summaries. The card is
 * {@code dd,ccccc,ddddd,f}: expiry days, checkpoint frequency, checkpoint display frequency and the debug flag.
 */
@Component
public final class Cbpaup0c implements DliProgram {

    public static final String NAME = "CBPAUP0C";
    public static final String PSB = "PSBPAUTB";

    private static final int PARM_LENGTH = 17;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String psb() {
        return PSB;
    }

    @Override
    public int run(Step step, ImsRegion region) {
        try {
            new Run(step, region).main();
            return 0;
        } catch (ImsGoback e) {
            return ImsGoback.RETURN_CODE;
        }
    }

    /** PRM-INFO as 1000-INITIALIZE leaves it. */
    private record Parameters(String card, int expiryDays, String checkpointFrequency, String checkpointDisplayFrequency,
            boolean debug) {

        static Parameters of(String card) {
            String expiry = card.substring(0, 2);
            return new Parameters(card,
                    expiry.chars().allMatch(c -> c >= '0' && c <= '9') ? Integer.parseInt(expiry) : 5,
                    defaulted(card.substring(3, 8), "5"),
                    defaulted(card.substring(9, 14), "10"),
                    card.charAt(15) == 'Y');
        }

        /** {@code IF P = SPACES OR 0 OR LOW-VALUES MOVE n TO P}, an alphanumeric move. */
        private static String defaulted(String parameter, String fallback) {
            return parameter.isBlank() || parameter.equals("0    ") || parameter.equals("\0".repeat(5))
                    ? (fallback + "    ").substring(0, 5)
                    : parameter;
        }
    }

    private static final class Run {

        private final Step step;
        private final ImsRegion region;
        private final Pcb pcb;
        private final Parameters parm;
        private final int today;
        private long summariesRead;
        private long summariesDeleted;
        private long detailsRead;
        private long detailsDeleted;
        private long summariesSinceCheckpoint;
        private long checkpoints;
        private long currentAccount;

        Run(Step step, ImsRegion region) {
            this.step = step;
            this.region = region;
            this.pcb = region.database();
            this.parm = Parameters.of(sysin(step));
            LocalDate date = LocalDate.now(step.clock());
            this.today = date.getYear() % 100 * 1000 + date.getDayOfYear();
        }

        void main() {
            step.display("STARTING PROGRAM CBPAUP0C::");
            step.display("*-------------------------------------*");
            step.display("CBPAUP0C PARM RECEIVED :" + parm.card());
            step.display("TODAYS DATE            :" + "%05d".formatted(today));
            step.display(" ");
            for (Get<PendingAuthSummary> root = nextSummary(); root.ok(); root = nextSummary()) {
                PendingAuthSummary inStorage = root.segment().orElseThrow();
                for (Get<PendingAuthDetail> child = nextDetail(inStorage); child.ok(); child = nextDetail(inStorage)) {
                    PendingAuthDetail detail = child.segment().orElseThrow();
                    if (expired(detail)) {
                        inStorage = subtractExpired(inStorage, detail);
                        deleteDetail(inStorage, detail);
                    }
                }
                if (qualifiesForDelete(inStorage)) {
                    deleteSummary(inStorage);
                }
                if (compareAsText(summariesSinceCheckpoint, parm.checkpointFrequency()) > 0) {
                    checkpoint();
                    summariesSinceCheckpoint = 0;
                }
            }
            checkpoint();
            step.display(" ");
            step.display("*-------------------------------------*");
            step.display("# TOTAL SUMMARY READ  :" + comp(summariesRead));
            step.display("# SUMMARY REC DELETED :" + comp(summariesDeleted));
            step.display("# TOTAL DETAILS READ  :" + comp(detailsRead));
            step.display("# DETAILS REC DELETED :" + comp(detailsDeleted));
            step.display("*-------------------------------------*");
            step.display(" ");
        }

        /** 2000-FIND-NEXT-AUTH-SUMMARY: GN, ending at GB. */
        private Get<PendingAuthSummary> nextSummary() {
            debug("DEBUG: AUTH SMRY READ : " + comp(summariesRead));
            Get<PendingAuthSummary> root = pcb.gn(Ssa.summary());
            if (root.ok()) {
                summariesRead++;
                summariesSinceCheckpoint++;
                currentAccount = root.segment().orElseThrow().acctId();
            } else if (root.status() != DliStatus.GB) {
                step.display("AUTH SUMMARY READ FAILED  :" + root.status().code());
                step.display("SUMMARY READ BEFORE ABEND :" + comp(summariesRead));
                throw abend();
            }
            return root;
        }

        /** 3000-FIND-NEXT-AUTH-DTL: GNP, ending at GE or GB. */
        private Get<PendingAuthDetail> nextDetail(PendingAuthSummary inStorage) {
            debug("DEBUG: AUTH DTL READ : " + comp(detailsRead));
            Get<PendingAuthDetail> child = pcb.gnp(Ssa.detail());
            if (child.ok()) {
                detailsRead++;
            } else if (child.status() != DliStatus.GE && child.status() != DliStatus.GB) {
                step.display("AUTH DETAIL READ FAILED  :" + child.status().code());
                step.display("SUMMARY AUTH APP ID      :" + account(inStorage.acctId()));
                step.display("DETAIL READ BEFORE ABEND :" + comp(detailsRead));
                throw abend();
            }
            return child;
        }

        /**
         * 4000-CHECK-IF-EXPIRED. The day difference is today's YYDDD less the authorization's, kept in
         * {@code PIC S9(4) COMP}, which drops all but its last four digits.
         */
        private boolean expired(PendingAuthDetail detail) {
            int authDate = 99999 - detail.key().authDate9c();
            int dayDiff = (today - authDate) % 10000;
            return dayDiff >= parm.expiryDays();
        }

        @LegacyDefect(id = "CBPAUP0C-expired-counts-not-replaced",
                goldens = {"batch-auth-purge-partial-expiry"},
                behavior = "an expired authorization is subtracted from the summary's approved or declined count and"
                        + " amount in working storage only; no REPL follows, so the summary keeps its old totals",
                fix = "REPL the summary after its expired details are deleted, before the next GN")
        private static PendingAuthSummary subtractExpired(PendingAuthSummary s, PendingAuthDetail detail) {
            boolean approved = "00".equals(detail.authRespCode());
            return new PendingAuthSummary(s.acctId(), s.custId(), s.authStatus(), s.accountStatus(), s.creditLimit(),
                    s.cashLimit(), s.creditBalance(), s.cashBalance(),
                    approved ? s.approvedAuthCnt() - 1 : s.approvedAuthCnt(),
                    approved ? s.declinedAuthCnt() : s.declinedAuthCnt() - 1,
                    approved ? s.approvedAuthAmt().subtract(detail.approvedAmt()) : s.approvedAuthAmt(),
                    approved ? s.declinedAuthAmt() : s.declinedAuthAmt().subtract(detail.transactionAmt()),
                    s.filler());
        }

        /** 5000-DELETE-AUTH-DTL: DLET of the detail the last GNP returned. */
        private void deleteDetail(PendingAuthSummary inStorage, PendingAuthDetail detail) {
            debug("DEBUG: AUTH DTL DLET : " + account(inStorage.acctId()));
            DliStatus status = pcb.dlet(detail);
            if (status != DliStatus.OK) {
                step.display("AUTH DETAIL DELETE FAILED :" + status.code());
                step.display("AUTH APP ID               :" + account(inStorage.acctId()));
                throw abend();
            }
            detailsDeleted++;
        }

        @LegacyDefect(id = "CBPAUP0C-approved-count-tested-twice",
                behavior = "the summary delete test reads IF PA-APPROVED-AUTH-CNT <= 0 AND PA-APPROVED-AUTH-CNT <= 0,"
                        + " so a summary with no approved authorization left qualifies even while it has declined ones",
                fix = "test PA-DECLINED-AUTH-CNT in the second condition")
        private static boolean qualifiesForDelete(PendingAuthSummary inStorage) {
            return inStorage.approvedAuthCnt() <= 0;
        }

        @LegacyDefect(id = "CBPAUP0C-summary-dlet-without-hold",
                goldens = {"batch-auth-purge-summary-delete-dj"},
                behavior = "the summary DLET comes right after the GNP that ended its details with GE, which released"
                        + " the hold, so EXEC DLI answers DJ and 9999-ABEND ends the job RC 16 at the first summary"
                        + " whose approved count reaches zero; the detail deletes before it are committed",
                fix = "GU the summary by its ACCNTID before the DLET")
        private void deleteSummary(PendingAuthSummary inStorage) {
            debug("DEBUG: AUTH SMRY DLET : " + account(inStorage.acctId()));
            DliStatus status = pcb.dlet(inStorage);
            if (status != DliStatus.OK) {
                step.display("AUTH SUMMARY DELETE FAILED :" + status.code());
                step.display("AUTH APP ID                :" + account(inStorage.acctId()));
                throw abend();
            }
            summariesDeleted++;
        }

        /** 9000-TAKE-CHECKPOINT. */
        private void checkpoint() {
            region.checkpoint();
            checkpoints++;
            if (compareAsText(checkpoints, parm.checkpointDisplayFrequency()) >= 0) {
                checkpoints = 0;
                step.display("CHKP SUCCESS: AUTH COUNT - " + comp(summariesRead)
                        + ", APP ID - " + "%011d".formatted(currentAccount));
            }
        }

        private void debug(String line) {
            if (parm.debug()) {
                step.display(line);
            }
        }

        private ImsGoback abend() {
            return ImsGoback.of(step, "CBPAUP0C ABENDING ...");
        }
    }

    @LegacyDefect(id = "CBPAUP0C-checkpoint-compares-as-text",
            goldens = {"batch-auth-purge-nothing-expired"},
            behavior = "the checkpoint tests compare a PIC 9(8) counter with a PIC X(5) parameter, which COBOL does as"
                    + " text with the parameter space-filled, so frequency 00001 is reached only at 1000 summaries and"
                    + " the default 5 at 50000000",
            fix = "move the parameters to numeric fields (or test them NUMERIC) and compare numbers")
    static int compareAsText(long counter, String parameter) {
        return "%08d".formatted(counter).compareTo((parameter + "   ").substring(0, 8));
    }

    /** ACCEPT PRM-INFO FROM SYSIN: the first card, cut or space-filled to PRM-INFO. */
    private static String sysin(Step step) {
        SequentialInput sysin = step.input("SYSIN");
        String card = "";
        if (sysin.open() == FileStatus.OK) {
            if (sysin.read() instanceof Read.Found found) {
                card = new String(found.record(), ISO_8859_1);
            }
            sysin.close();
        }
        return (card + " ".repeat(PARM_LENGTH)).substring(0, PARM_LENGTH);
    }

    /** DISPLAY of a {@code PIC S9(8) COMP} counter. */
    private static String comp(long value) {
        return (value < 0 ? "-" : "+") + "%010d".formatted(Math.abs(value));
    }

    /** DISPLAY of PA-ACCT-ID, {@code PIC S9(11) COMP-3}. */
    private static String account(long acctId) {
        return "+" + "%011d".formatted(acctId);
    }
}
