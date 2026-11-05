package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.Arrays;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.Step;
import com.carddemo.db2.Db2;
import com.carddemo.db2.Sqlca;

/**
 * COBTUPDT: adds, updates and deletes rows of CARDDEMO.TRANSACTION_TYPE from the INPFILE records (column 1 the
 * action A, U, D or * for a comment, columns 2-3 the type code, 4-53 the description). An error DISPLAYs the
 * message and sets RETURN-CODE 4, and the run goes on. The step is one Db2 unit of work.
 */
@Component
public final class Cobtupdt implements BatchProgram {

    public static final String NAME = "COBTUPDT";

    private static final String INPFILE = "INPFILE";
    private static final int RECORD = 53;
    private static final int RETURN_MSG = 80;
    private static final int TYPE_CODE = 1;
    private static final int DESCRIPTION = 3;

    private final Db2 db2;

    public Cobtupdt(Db2 db2) {
        this.db2 = db2;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        return db2.unitOfWork(() -> new Run(step).execute());
    }

    private final class Run {

        private final Step step;
        private final char[] returnMessage = " ".repeat(RETURN_MSG).toCharArray();
        private int returnCode;

        Run(Step step) {
            this.step = step;
        }

        /**
         * A READ that fails other than at end leaves LASTREC unset, so the COBOL loops on the stale record until
         * the job is cancelled; this loop ends instead.
         */
        int execute() {
            SequentialInput input = step.input(INPFILE);
            step.display(input.open() == FileStatus.OK ? "OPEN FILE OK" : "OPEN FILE NOT OK");
            for (Read read = input.read(); read instanceof Read.Found found; read = input.read()) {
                String record = inputRecord(found.record());
                step.display("PROCESSING   " + record);
                treat(record);
            }
            input.close();
            return returnCode;
        }

        private void treat(String record) {
            switch (record.charAt(0)) {
                case 'A' -> {
                    step.display("ADDING RECORD");
                    insert(record);
                }
                case 'U' -> {
                    step.display("UPDATING RECORD");
                    update(record);
                }
                case 'D' -> {
                    step.display("DELETING RECORD");
                    delete(record);
                }
                case '*' -> step.display("IGNORING COMMENTED LINE");
                default -> {
                    string("ERROR: TYPE NOT VALID");
                    abend();
                }
            }
        }

        private void insert(String record) {
            Sqlca sqlca = sql("INSERT INTO CARDDEMO.TRANSACTION_TYPE (TR_TYPE, TR_DESCRIPTION) VALUES (?, ?)",
                    typeCode(record), description(record));
            if (sqlca.sqlcode() == 0) {
                step.display("RECORD INSERTED SUCCESSFULLY");
            } else if (sqlca.sqlcode() < 0) {
                tableError(sqlca);
            }
        }

        private void update(String record) {
            Sqlca sqlca = sql("UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = ? WHERE TR_TYPE = ?",
                    description(record), typeCode(record));
            changed(sqlca, "RECORD UPDATED SUCCESSFULLY");
        }

        private void delete(String record) {
            Sqlca sqlca = sql("DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?", typeCode(record));
            changed(sqlca, "RECORD DELETED SUCCESSFULLY");
        }

        private void changed(Sqlca sqlca, String success) {
            if (sqlca.sqlcode() == 0) {
                step.display(success);
            } else if (sqlca.sqlcode() == Sqlca.NOT_FOUND) {
                string("No records found.");
                abend();
            } else if (sqlca.sqlcode() < 0) {
                tableError(sqlca);
            }
        }

        private void tableError(Sqlca sqlca) {
            string("Error accessing: TRANSACTION_TYPE table. SQLCODE:" + sqlca.sqlcodeEdited());
            abend();
        }

        /** 9999-ABEND, which does not abend. */
        private void abend() {
            step.display(new String(returnMessage));
            returnCode = 4;
        }

        @LegacyDefect(id = "COBTUPDT-sql-resets-return-code",
                goldens = {"batch-tran-type-later-sql-resets-rc"},
                behavior = "9999-ABEND sets RETURN-CODE to 4, but the CALL the precompiler generates for the next SQL "
                        + "statement sets RETURN-CODE again, so an error followed by a statement that succeeds ends "
                        + "the step RC 0",
                fix = "keep the highest return code in working storage and move it to RETURN-CODE before STOP RUN")
        private Sqlca sql(String statement, Object... hostVariables) {
            returnCode = 0;
            return db2.execute(statement, hostVariables);
        }

        @LegacyDefect(id = "COBTUPDT-message-keeps-stale-tail",
                goldens = {"batch-tran-type-message-keeps-stale-tail"},
                behavior = "STRING ... DELIMITED BY SIZE INTO WS-RETURN-MSG replaces only the characters it moves and "
                        + "the message is never cleared, so a shorter message DISPLAYs with the tail of a longer one",
                fix = "MOVE SPACES TO WS-RETURN-MSG before each STRING")
        private void string(String text) {
            text.getChars(0, Math.min(text.length(), RETURN_MSG), returnMessage, 0);
        }
    }

    private static String inputRecord(byte[] record) {
        byte[] padded = Arrays.copyOf(record, RECORD);
        Arrays.fill(padded, Math.min(record.length, RECORD), RECORD, (byte) ' ');
        return new String(padded, ISO_8859_1);
    }

    @LegacyDefect(id = "COBTUPDT-type-code-not-validated",
            goldens = {"batch-tran-type-code-not-validated"},
            behavior = "the two-byte type code is bound as read, although MNTTRDB2 documents a numeric value, so "
                    + "letters and a blank second byte are added",
            fix = "reject a record whose columns 2-3 are not two digits")
    private static String typeCode(String record) {
        return record.substring(TYPE_CODE, DESCRIPTION);
    }

    @LegacyDefect(id = "COBTUPDT-description-keeps-trailing-blanks",
            goldens = {"batch-tran-type-add-keeps-trailing-blanks", "batch-tran-type-add-update-delete",
                    "batch-tran-type-later-sql-resets-rc", "batch-tran-type-code-not-validated"},
            behavior = "INSERT and UPDATE bind the fixed 50-byte INPUT-REC-DESC to the VARCHAR(50) TR_DESCRIPTION, so "
                    + "every description is stored with its trailing blanks, length 50",
            fix = "bind a VARCHAR host variable that holds the description without its trailing blanks")
    private static String description(String record) {
        return record.substring(DESCRIPTION, RECORD);
    }
}
