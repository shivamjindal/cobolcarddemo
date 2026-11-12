package com.carddemo.online;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** The installed programs and the rules every screen shares; both HTTP adapters go through here. */
@Component
public class Screens {

    private static final DateTimeFormatter CURDATE = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter CURTIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Map<Program, ScreenProgram> installed = new EnumMap<>(Program.class);
    private final Clock clock;

    public Screens(List<ScreenProgram> programs, Clock clock) {
        programs.forEach(p -> {
            if (installed.put(p.program(), p) != null) {
                throw new IllegalStateException(p.program() + " is installed twice");
            }
        });
        this.clock = clock;
    }

    public boolean installed(Program program) {
        return installed.containsKey(program);
    }

    /** Parses a program name from a URL: unknown and not-installed programs are 404. */
    public Program program(String name) {
        return Arrays.stream(Program.values()).filter(p -> p.name().equals(name) && installed(p)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Outcome arrive(Program program, Conversation conversation) {
        return sent(program, conversation, permitted(program, conversation).arrive(conversation));
    }

    public Outcome receive(Program program, Conversation conversation, Aid aid, Map<String, String> fields) {
        Map<String, String> trimmed = fields.entrySet().stream().filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stripTrailing()));
        conversation.shown().filter(shown -> shown.program() == program)
                .ifPresent(shown -> trimmed.putAll(shown.values()));
        Outcome outcome = permitted(program, conversation).receive(conversation, aid, trimmed);
        if (outcome instanceof Outcome.Transfer) {
            conversation.from(program);
        }
        return sent(program, conversation, outcome);
    }

    /** A protected field comes back as it was sent (DFHBMPRF), so the client cannot change it. */
    private static Outcome sent(Program program, Conversation conversation, Outcome outcome) {
        if (outcome instanceof Outcome.Show show) {
            Map<String, String> values = new TreeMap<>();
            show.readOnly().forEach(name -> values.put(name, show.fields().getOrDefault(name, "")));
            conversation.put(new Conversation.Protected(program, values));
        }
        return outcome;
    }

    /** Adds the header every map carries (POPULATE-HEADER-INFO, titles from COTTL01Y). */
    public Screen render(Program program, Outcome.Show show) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("TRNNAME", program.transid);
        fields.put("PGMNAME", program.name());
        fields.put("TITLE01", "AWS Mainframe Modernization");
        fields.put("TITLE02", "CardDemo");
        fields.put("CURDATE", CURDATE.format(now));
        fields.put("CURTIME", CURTIME.format(now));
        fields.putAll(new TreeMap<>(show.fields()));
        return new Screen(program, program.transid, fields, show.message(), show.cursor(), show.readOnly());
    }

    /** No legacy program checks the user type; an admin-only program is 403 for a regular user. */
    private ScreenProgram permitted(Program program, Conversation conversation) {
        if (program.access == Program.Access.ADMIN && conversation.user().type() != UserType.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return installed.get(program);
    }
}
