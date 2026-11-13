package com.carddemo.authorizations;

import com.carddemo.batch.PendingAuthorizationPurge;
import com.carddemo.batch.StepOutput;
import org.springframework.batch.core.JobParameters;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

/**
 * CBPAUP0C: deletes the pending authorizations at least the SYSIN expiry days old, and each summary then left with
 * no approved and no declined ones. The counters drop only in memory to decide that: as in the legacy program, a
 * summary that stays is not rewritten. Each summary is one transaction, where the BMP committed at checkpoints.
 */
@Component
class ExpiredAuthorizationPurge implements PendingAuthorizationPurge.Purge {

    /** The job parameter that stands for SYSIN; without it the step reads CBPAUP0J's in-stream data. */
    static final String SYSIN = "sysin";
    private static final String JCL_SYSIN = "00,00001,00001,Y";
    private static final String RULE = "*-------------------------------------*";

    private final PendingAuthorizations pending;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ExpiredAuthorizationPurge(PendingAuthorizations pending, TransactionTemplate transactions, Clock clock) {
        this.pending = pending;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void run(JobParameters parameters, StepOutput out) {
        new Pass(Parm.of(Optional.ofNullable(parameters.getString(SYSIN)).orElse(JCL_SYSIN)), out).run();
    }

    /**
     * PRM-INFO, PIC 9(02) X X(05) X X(05) X X X. A non-numeric expiry is 5 days, and a blank or zero checkpoint
     * field takes its default, both kept as the text the program compares.
     */
    private record Parm(String text, int expiryDays, String checkpointFrequency, String displayFrequency,
                        boolean debug) {

        static Parm of(String sysin) {
            String text = Pictures.pad(sysin, 17);
            String days = text.substring(0, 2);
            return new Parm(text, days.matches("\\d\\d") ? Integer.parseInt(days) : 5,
                    orDefault(text.substring(3, 8), "5    "), orDefault(text.substring(9, 14), "10   "),
                    text.charAt(15) == 'Y');
        }

        private static String orDefault(String field, String otherwise) {
            return field.isBlank() || field.chars().allMatch(c -> c == '0') ? otherwise : field;
        }
    }

    /** One run of the program: its WS-VARIABLES counters and the SYSOUT it DISPLAYs. */
    private final class Pass {

        private final Parm parm;
        private final StepOutput out;
        private int summariesRead;
        private int summariesDeleted;
        private int detailsRead;
        private int detailsDeleted;
        private int sinceCheckpoint;
        private int checkpoints;
        private long account;

        Pass(Parm parm, StepOutput out) {
            this.parm = parm;
            this.out = out;
        }

        void run() {
            LocalDate today = LocalDate.now(clock);
            int yyddd = today.getYear() % 100 * 1000 + today.getDayOfYear();
            out.display("STARTING PROGRAM CBPAUP0C::");
            out.display(RULE);
            out.display("CBPAUP0C PARM RECEIVED :" + parm.text());
            out.display("TODAYS DATE            :" + "%05d".formatted(yyddd));
            out.display(" ");
            debug("DEBUG: AUTH SMRY READ : " + signed(summariesRead, 8));
            for (AuthorizationSummary summary : pending.summaries()) {
                summariesRead++;
                sinceCheckpoint++;
                account = summary.accountId();
                transactions.executeWithoutResult(tx -> purge(summary, yyddd));
                if (compareAsText(sinceCheckpoint, parm.checkpointFrequency()) > 0) {
                    checkpoint();
                    sinceCheckpoint = 0;
                }
                debug("DEBUG: AUTH SMRY READ : " + signed(summariesRead, 8));
            }
            checkpoint();
            out.display(" ");
            out.display(RULE);
            out.display("# TOTAL SUMMARY READ  :" + signed(summariesRead, 8));
            out.display("# SUMMARY REC DELETED :" + signed(summariesDeleted, 8));
            out.display("# TOTAL DETAILS READ  :" + signed(detailsRead, 8));
            out.display("# DETAILS REC DELETED :" + signed(detailsDeleted, 8));
            out.display(RULE);
            out.display(" ");
        }

        /**
         * 3000 to 6000 for one summary. The days are YYDDD minus YYDDD, so across a new year every older detail
         * counts as hundreds of days old. A summary goes once both counters reach zero, where the legacy test
         * checked the approved count twice and so also deleted unexpired declines.
         */
        private void purge(AuthorizationSummary summary, int today) {
            int approved = summary.approvedCount();
            int declined = summary.declinedCount();
            debug("DEBUG: AUTH DTL READ : " + signed(detailsRead, 8));
            for (Authorization detail : pending.details(summary.accountId())) {
                detailsRead++;
                if (today - detail.key().yyddd() >= parm.expiryDays()) {
                    if (detail.approved()) {
                        approved--;
                    } else {
                        declined--;
                    }
                    debug("DEBUG: AUTH DTL DLET : " + signed(summary.accountId(), 11));
                    pending.delete(detail);
                    detailsDeleted++;
                }
                debug("DEBUG: AUTH DTL READ : " + signed(detailsRead, 8));
            }
            if (approved <= 0 && declined <= 0) {
                debug("DEBUG: AUTH SMRY DLET : " + signed(summary.accountId(), 11));
                pending.deleteSummary(summary.accountId());
                summariesDeleted++;
            }
        }

        /** 9000-TAKE-CHECKPOINT, which reports only every P-CHKP-DIS-FREQ checkpoints. */
        private void checkpoint() {
            checkpoints++;
            if (compareAsText(checkpoints, parm.displayFrequency()) >= 0) {
                checkpoints = 0;
                out.display("CHKP SUCCESS: AUTH COUNT - " + signed(summariesRead, 8) + ", APP ID - "
                        + "%011d".formatted(account));
            }
        }

        private void debug(String line) {
            if (parm.debug()) {
                out.display(line);
            }
        }
    }

    /**
     * A 9(8) counter against an X(05) PARM field, which COBOL compares as text with the shorter padded with spaces:
     * with the JCL's 00001 the counter exceeds it only from 1000 on.
     */
    private static int compareAsText(int counter, String field) {
        return "%08d".formatted(counter).compareTo(Pictures.pad(field, 8));
    }

    /** DISPLAY of a signed numeric item: its sign, then every digit. */
    private static String signed(long value, int digits) {
        return (value < 0 ? "-" : "+") + ("%0" + digits + "d").formatted(Math.abs(value));
    }
}
