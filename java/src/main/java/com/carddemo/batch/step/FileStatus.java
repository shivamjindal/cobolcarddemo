package com.carddemo.batch.step;

/** The two-character FILE STATUS of an I/O statement. */
public enum FileStatus {
    OK("00"),
    DUPLICATE_ALTERNATE_KEY("02"),
    END_OF_FILE("10"),
    DUPLICATE_KEY("22"),
    NOT_FOUND("23"),
    FILE_NOT_FOUND("35"),
    MODE_NOT_SUPPORTED("37"),
    ALREADY_OPEN("41"),
    NOT_OPEN("42"),
    RECORD_LENGTH("44"),
    NO_NEXT_RECORD("46"),
    NOT_OPEN_FOR_READ("47"),
    NOT_OPEN_FOR_WRITE("48"),
    NOT_OPEN_FOR_REWRITE("49");

    private final String code;

    FileStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean successful() {
        return code.charAt(0) == '0';
    }
}
