package com.carddemo.batch.programs;

import com.carddemo.batch.step.Abend;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.Step;

/** How CardDemo batch programs end on a failed OPEN, READ, WRITE or CLOSE: 9910-DISPLAY-IO-STATUS, then 9999-ABEND-PROGRAM. */
final class IoFailure {

    private IoFailure() {}

    /** DISPLAYs {@code message} and the status, then abends U0999; the caller throws the result. */
    static Abend abend(Step step, String message, FileStatus status) {
        step.display(message);
        displayIoStatus(step, status.code());
        return abendProgram(step);
    }

    /**
     * 9910-DISPLAY-IO-STATUS. A numeric status not starting with 9 shows as {@code 00} and the status. Otherwise
     * the first byte shows as is and the second as the three-digit value of its byte, through PIC 9(4) BINARY.
     */
    static void displayIoStatus(Step step, String status) {
        char first = status.charAt(0);
        boolean numeric = status.chars().allMatch(c -> c >= '0' && c <= '9');
        step.display("FILE STATUS IS: NNNN" + (!numeric || first == '9'
                ? first + "%03d".formatted((int) status.charAt(1))
                : "00" + status));
    }

    /** 9999-ABEND-PROGRAM: CEE3ABD with ABCODE 999 and TIMING 0. */
    static Abend abendProgram(Step step) {
        step.display("ABENDING PROGRAM");
        return Abend.user(999);
    }
}
