package com.carddemo.online;

import com.carddemo.users.Credentials;
import org.assertj.core.api.SoftAssertions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One case from characterization/online/specs, with its entry from spec-overrides/ applied. An
 * override replaces whole keys of given, when or then, and may add {@code then.status}.
 */
record SpecCase(String program, String id, String summary, Map<String, Object> given, Map<String, Object> when,
                Map<String, Object> then, Kind kind, String reason) {

    enum Kind { AS_SPECIFIED, OVERRIDDEN, SKIPPED }

    private static final Path SPECS = Path.of(System.getProperty("carddemo.specs"));
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]*)}");

    @SuppressWarnings("unchecked")
    static List<SpecCase> load(String program) {
        Map<String, Map<String, Object>> overrides = new HashMap<>(overrides(program));
        List<SpecCase> cases = new ArrayList<>();
        for (Map<String, Object> spec : (List<Map<String, Object>>) yaml(SPECS.resolve(program + ".yaml")).get("cases")) {
            String id = (String) spec.get("id");
            Map<String, Object> override = overrides.remove(id);
            if (override == null) {
                cases.add(new SpecCase(program, id, (String) spec.get("summary"), section(spec, "given"),
                        section(spec, "when"), section(spec, "then"), Kind.AS_SPECIFIED, null));
                continue;
            }
            String reason = (String) override.get("reason");
            if (reason == null || reason.isBlank()) {
                throw new IllegalStateException(id + " is overridden without a reason");
            }
            cases.add(new SpecCase(program, id, (String) spec.get("summary"),
                    merge(spec, override, "given"), merge(spec, override, "when"), merge(spec, override, "then"),
                    Boolean.TRUE.equals(override.get("skip")) ? Kind.SKIPPED : Kind.OVERRIDDEN, reason));
        }
        if (!overrides.isEmpty()) {
            throw new IllegalStateException("Overrides name cases " + program + " does not have: " + overrides.keySet());
        }
        return cases;
    }

    String entry() {
        return (String) given.getOrDefault("entry", "reenter");
    }

    String aid() {
        return (String) when.getOrDefault("aid", "ENTER");
    }

    /** when.fields keyed by BMS base name (USERIDI becomes USERID). */
    Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        map(when.get("fields")).forEach((name, value) -> fields.put(strip(name, "I"), text(value)));
        return fields;
    }

    /** given.files by CICS file name. */
    @SuppressWarnings("unchecked")
    Map<String, List<Map<String, Object>>> files() {
        return (Map<String, List<Map<String, Object>>>) (Map<String, ?>) map(given.get("files"));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fileConditions() {
        return (List<Map<String, Object>>) given.getOrDefault("file_conditions", List.of());
    }

    /** given.commarea as a Conversation. CDEMO-PGM-CONTEXT is implicit in GET versus POST. */
    Conversation conversation() {
        Map<String, Object> commarea = map(given.get("commarea"));
        String type = text(commarea.getOrDefault("CDEMO-USER-TYPE", "A"));
        String user = text(commarea.getOrDefault("CDEMO-USER-ID", ""));
        if (user.isBlank()) {
            user = type.equals("A") ? "ADMIN001" : "USER0001";
        }
        String from = text(commarea.getOrDefault("CDEMO-FROM-PROGRAM", ""));
        Conversation conversation = new Conversation(new SignedOnUser(user, UserType.stored(type)),
                from.isBlank() ? null : Program.valueOf(from));
        CommareaMapping.of(program).load(program, commarea, conversation);
        return conversation;
    }

    void verify(Observed observed, Datasets datasets) {
        SoftAssertions soft = new SoftAssertions();
        if (then.containsKey("status")) {
            soft.assertThat(observed.status()).as("status").isEqualTo(then.get("status"));
        }
        if (then.containsKey("transfer")) {
            Map<String, Object> transfer = map(then.get("transfer"));
            soft.assertThat(observed.transfer()).as("transfer")
                    .isEqualTo(transfer.isEmpty() ? null : transfer.get("via") + " " + transfer.get("to"));
        }
        if (then.containsKey("message")) {
            String message = (String) then.get("message");
            if (message == null) {
                soft.assertThat(observed.message()).as("message").isNull();
            } else {
                soft.assertThat(observed.message()).as("message").isNotNull().matches(pattern(message));
            }
        }
        if (then.containsKey("cursor")) {
            String cursor = (String) then.get("cursor");
            soft.assertThat(observed.cursor()).as("cursor").isEqualTo(cursor == null ? null : strip(cursor, "L"));
        }
        map(then.get("fields")).forEach((name, value) -> soft.assertThat(
                observed.fields().getOrDefault(strip(name, "O"), "")).as(name).isEqualTo(text(value)));
        if (then.containsKey("commarea") && !"XCTL COSGN00C".equals(observed.transfer())) {
            soft.assertThat(observed.conversation()).as("conversation").isNotNull();
            if (observed.conversation() != null) {
                map(then.get("commarea")).forEach((name, value) -> commarea(observed.conversation(), name)
                        .ifPresent(actual -> soft.assertThat(actual).as(name).isEqualTo(
                                name.endsWith("-USR-SEL-FLG") ? text(value).toUpperCase(Locale.ROOT) : text(value))));
            }
        }
        if (then.containsKey("file_effects")) {
            verifyFileEffects(soft, observed.fileEffects(), datasets);
        }
        soft.assertAll();
    }

    @SuppressWarnings("unchecked")
    private void verifyFileEffects(SoftAssertions soft, List<Datasets.Effect> actual, Datasets datasets) {
        List<Map<String, Object>> expected = (List<Map<String, Object>>) then.get("file_effects");
        soft.assertThat(actual).as("file_effects").hasSize(expected.size());
        for (Map<String, Object> effect : expected) {
            String file = (String) effect.get("file");
            Map<String, Object> record = map(effect.get("record"));
            List<String> key = datasets.keyFields(file);
            Optional<Datasets.Effect> match = actual.stream().filter(a -> a.op().equals(effect.get("op"))
                    && Datasets.FILES.get(a.file()) == Datasets.FILES.get(file)
                    && key.stream().allMatch(k -> a.record().get(k).equals(text(record.get(k))))).findFirst();
            soft.assertThat(match).as("%s %s %s", effect.get("op"), file, key.stream().map(record::get).toList()).isPresent();
            match.ifPresent(a -> record.forEach((name, value) -> {
                if (name.equals("SEC-USR-PWD")) {
                    soft.assertThat(Credentials.matches(text(value), a.record().get(name))).as(name).isTrue();
                } else {
                    soft.assertThat(a.record().get(name)).as("%s %s", file, name).isEqualTo(text(value));
                }
            }));
        }
    }

    /** Commarea fields the Java side models; an empty result means the field is implicit and not compared. */
    private Optional<String> commarea(Conversation conversation, String name) {
        return switch (name) {
            case "CDEMO-PGM-CONTEXT", "CDEMO-TO-PROGRAM", "CDEMO-TO-TRANID" -> Optional.empty();
            case "CDEMO-FROM-PROGRAM" -> Optional.of(conversation.from().map(Program::name).orElse(""));
            case "CDEMO-FROM-TRANID" -> Optional.of(conversation.from().map(p -> p.transid).orElse(""));
            case "CDEMO-USER-ID" -> Optional.of(conversation.user().id());
            case "CDEMO-USER-TYPE" -> Optional.of(conversation.user().type().code);
            default -> CommareaMapping.of(program).read(name, conversation);
        };
    }

    /** "{name}" is a runtime value and "{}" joins two literals, per SCHEMA.md. */
    static Pattern pattern(String message) {
        StringBuilder regex = new StringBuilder();
        Matcher placeholder = PLACEHOLDER.matcher(message);
        int literal = 0;
        while (placeholder.find()) {
            regex.append(Pattern.quote(message.substring(literal, placeholder.start())))
                    .append(placeholder.group(1).isEmpty() ? "" : ".+?");
            literal = placeholder.end();
        }
        return Pattern.compile(regex.append(Pattern.quote(message.substring(literal))).toString());
    }

    /** LOW-VALUES in a spec is a field or COMMAREA item never set, which the Java side holds as blank. */
    static String text(Object value) {
        return value == null || "LOW-VALUES".equals(value) ? "" : String.valueOf(value);
    }

    @Override
    public String toString() {
        return id + " " + summary;
    }

    private static String strip(String name, String suffix) {
        return name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    private static Map<String, Object> section(Map<String, Object> spec, String name) {
        return new LinkedHashMap<>(map(spec.get(name)));
    }

    private static Map<String, Object> merge(Map<String, Object> spec, Map<String, Object> override, String name) {
        Map<String, Object> merged = section(spec, name);
        merged.putAll(map(override.get(name)));
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> overrides(String program) {
        try (InputStream in = SpecCase.class.getResourceAsStream("/spec-overrides/" + program + ".yaml")) {
            return in == null ? Map.of() : new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Specs share fixtures through YAML aliases, more than SnakeYAML's default limit of 50. */
    private static Map<String, Object> yaml(Path file) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(Integer.MAX_VALUE);
        try (InputStream in = Files.newInputStream(file)) {
            return new Yaml(options).load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
