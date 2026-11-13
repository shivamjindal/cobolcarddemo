package com.carddemo.batch;

import com.carddemo.trantypes.TranType;
import com.carddemo.trantypes.TranTypeRepository;
import org.springframework.batch.core.JobParameters;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * MNTTRDB2 (COBTUPDT): applies an input file of A/U/D requests to tran_types. Run on demand.
 * Control-M schedules it weekly under WEEKLY-TransactionTypesDBRefresh, not in the daily or monthly chains.
 * The message area is not cleared between failures, so a later message keeps the tail of the previous one.
 */
@Component
public class TransactionTypeMaintenance implements JclJob {

    public static final String INPUT = "input";

    private final TranTypeRepository types;
    private final JdbcTemplate jdbc;

    public TransactionTypeMaintenance(TranTypeRepository types, JdbcTemplate jdbc) {
        this.types = types;
        this.jdbc = jdbc;
    }

    @Override
    public String jobName() {
        return "MNTTRDB2";
    }

    @Override
    public String programName() {
        return "COBTUPDT";
    }

    @Override
    public void run(JobParameters parameters, StepOutput out) {
        String path = parameters.getString(INPUT);
        if (path == null || path.isBlank()) {
            out.display("OPEN FILE NOT OK");
            out.returnCode(4);
            return;
        }
        List<String> records;
        try {
            records = records(Path.of(path));
        } catch (IOException e) {
            out.display("OPEN FILE NOT OK");
            out.returnCode(4);
            return;
        }
        out.display("OPEN FILE OK");
        char[] message = new char[80];
        Arrays.fill(message, ' ');
        int returnCode = 0;
        for (String record : records) {
            out.display("PROCESSING   " + record);
            String action = record.substring(0, 1);
            String code = record.substring(1, 3);
            String description = record.substring(3).stripTrailing();
            switch (action) {
                case "A" -> {
                    out.display("ADDING RECORD");
                    try {
                        types.insert(new TranType(code, description));
                        out.display("RECORD INSERTED SUCCESSFULLY");
                    } catch (DuplicateKeyException e) {
                        copy(message, "Error accessing: TRANSACTION_TYPE table. SQLCODE:" + pic(e));
                        out.display(new String(message));
                        returnCode = 4;
                    } catch (DataAccessException e) {
                        copy(message, "Error accessing: TRANSACTION_TYPE table. SQLCODE:" + pic(e));
                        out.display(new String(message));
                        returnCode = 4;
                    }
                }
                case "U" -> {
                    out.display("UPDATING RECORD");
                    try {
                        if (types.update(new TranType(code, description))) {
                            out.display("RECORD UPDATED SUCCESSFULLY");
                        } else {
                            copy(message, "No records found.");
                            out.display(new String(message));
                            returnCode = 4;
                        }
                    } catch (DataAccessException e) {
                        copy(message, "Error accessing: TRANSACTION_TYPE table. SQLCODE:" + pic(e));
                        out.display(new String(message));
                        returnCode = 4;
                    }
                }
                case "D" -> {
                    out.display("DELETING RECORD");
                    try {
                        switch (types.delete(code)) {
                            case DELETED -> out.display("RECORD DELETED SUCCESSFULLY");
                            case MISSING -> {
                                copy(message, "No records found.");
                                out.display(new String(message));
                                returnCode = 4;
                            }
                            case RESTRICTED -> {
                                copy(message, "Error accessing: TRANSACTION_TYPE table. SQLCODE: -532");
                                out.display(new String(message));
                                returnCode = 4;
                            }
                        }
                    } catch (DataAccessException e) {
                        copy(message, "Error accessing: TRANSACTION_TYPE table. SQLCODE:" + pic(e));
                        out.display(new String(message));
                        returnCode = 4;
                    }
                }
                case "*" -> out.display("IGNORING COMMENTED LINE");
                default -> {
                    copy(message, "ERROR: TYPE NOT VALID");
                    out.display(new String(message));
                    returnCode = 4;
                }
            }
        }
        List<String> remaining = jdbc.query("SELECT tran_type, tran_type_desc FROM tran_types ORDER BY tran_type",
                (rs, row) -> rs.getString(1) + rs.getString(2).stripTrailing());
        out.dataset("TRANTYPE", remaining);
        out.returnCode(returnCode);
    }

    /** PIC ----9, which is what COBTUPDT moves SQLCODE into before displaying it. Duplicate is -803. */
    private static String pic(DataAccessException e) {
        int code = e instanceof DuplicateKeyException ? -803 : -904;
        String body = (code < 0 ? "-" : "") + Integer.toString(Math.abs(code));
        return " ".repeat(5 - body.length()) + body;
    }

    private static void copy(char[] message, String text) {
        for (int i = 0; i < text.length() && i < message.length; i++) {
            message[i] = text.charAt(i);
        }
    }

    static List<String> records(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0) {
            return List.of();
        }
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        if (text.indexOf('\n') >= 0) {
            return text.lines().map(TransactionTypeMaintenance::pad).toList();
        }
        if (bytes.length % 53 != 0) {
            throw new UncheckedIOException(new IOException(path + " is not 53-byte records"));
        }
        List<String> records = new java.util.ArrayList<>();
        for (int i = 0; i < bytes.length; i += 53) {
            records.add(text.substring(i, i + 53));
        }
        return records;
    }

    private static String pad(String line) {
        String op = line.isEmpty() ? " " : line.substring(0, 1);
        String number = (line.length() > 1 ? line.substring(1, Math.min(3, line.length())) : "").concat("  ").substring(0, 2);
        String description = (line.length() > 3 ? line.substring(3) : "");
        description = (description + " ".repeat(50)).substring(0, 50);
        return op + number + description;
    }
}
