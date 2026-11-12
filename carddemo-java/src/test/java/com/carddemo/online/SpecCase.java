package com.carddemo.online;

import com.carddemo.users.Credentials;
import com.carddemo.users.UserListPage;
import com.carddemo.users.UserSelection;
import org.assertj.core.api.SoftAssertions;
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

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> users() {
        return (List<Map<String, Object>>) map(given.get("files")).getOrDefault("USRSEC", List.of());
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fileConditions() {
        return (List<Map<String, Object>>) given.getOrDefault("file_conditions", List.of());
    }

    /** given.commarea as a Conversation. CDEMO-PGM-CONTEXT is implicit in GET versus POST. */
    Conversation conversation() {
        Map<String, Object> commarea = map(given.get("commarea"));
        String type = text(commarea.getOrDefault("CDEMO-USER-TYPE", "A"));
        String user = text(commarea.getOrDefault("CDEMO-USER-ID", type.equals("A") ? "ADMIN001" : "USER0001"));
        String from = text(commarea.getOrDefault("CDEMO-FROM-PROGRAM", ""));
        Conversation conversation = new Conversation(new SignedOnUser(user, UserType.stored(type)),
                from.isBlank() ? null : Program.valueOf(from));
        Map<String, String> info = new HashMap<>();
        commarea.forEach((key, value) -> {
            if (key.matches("CDEMO-CU0\\d-.+")) {
                info.put(key.substring("CDEMO-CU00-".length()), text(value));
            }
        });
        if (info.keySet().stream().anyMatch(k -> k.startsWith("USRID-") || k.equals("PAGE-NUM") || k.equals("NEXT-PAGE-FLG"))) {
            conversation.put(new UserListPage(info.getOrDefault("USRID-FIRST", ""), info.getOrDefault("USRID-LAST", ""),
                    Integer.parseInt(info.getOrDefault("PAGE-NUM", "1")), "Y".equals(info.get("NEXT-PAGE-FLG"))));
        }
        String selected = info.getOrDefault("USR-SELECTED", "");
        if (!selected.isBlank()) {
            String flag = info.getOrDefault("USR-SEL-FLG", program.equals("COUSR03C") ? "D" : "U");
            conversation.put(new UserSelection(selected,
                    flag.equalsIgnoreCase("D") ? UserSelection.Action.DELETE : UserSelection.Action.UPDATE));
        }
        return conversation;
    }

    void verify(Observed observed) {
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
            soft.assertThat(observed.cursor()).as("cursor").isEqualTo(strip((String) then.get("cursor"), "L"));
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
            verifyFileEffects(soft, observed.fileEffects());
        }
        soft.assertAll();
    }

    @SuppressWarnings("unchecked")
    private void verifyFileEffects(SoftAssertions soft, List<UsersTable.Effect> actual) {
        List<Map<String, Object>> expected = (List<Map<String, Object>>) then.get("file_effects");
        soft.assertThat(actual).as("file_effects").hasSize(expected.size());
        for (Map<String, Object> effect : expected) {
            Map<String, Object> record = map(effect.get("record"));
            Optional<UsersTable.Effect> match = actual.stream().filter(a -> a.op().equals(effect.get("op"))
                    && a.record().get("SEC-USR-ID").equals(record.get("SEC-USR-ID"))).findFirst();
            soft.assertThat(match).as("%s %s", effect.get("op"), record.get("SEC-USR-ID")).isPresent();
            match.ifPresent(a -> record.forEach((name, value) -> {
                if (name.equals("SEC-USR-PWD")) {
                    soft.assertThat(Credentials.matches(text(value), a.record().get(name))).as(name).isTrue();
                } else {
                    soft.assertThat(a.record().get(name)).as(name).isEqualTo(text(value));
                }
            }));
        }
    }

    /** Commarea fields the Java side models; an empty result means the field is implicit and not compared. */
    private static Optional<String> commarea(Conversation conversation, String name) {
        Optional<UserListPage> page = conversation.get(UserListPage.class);
        Optional<UserSelection> selection = conversation.get(UserSelection.class);
        return switch (name.replaceFirst("^CDEMO-CU0\\d-", "CDEMO-CUnn-")) {
            case "CDEMO-PGM-CONTEXT", "CDEMO-TO-PROGRAM" -> Optional.empty();
            case "CDEMO-FROM-PROGRAM" -> Optional.of(conversation.from().map(Program::name).orElse(""));
            case "CDEMO-FROM-TRANID" -> Optional.of(conversation.from().map(p -> p.transid).orElse(""));
            case "CDEMO-USER-ID" -> Optional.of(conversation.user().id());
            case "CDEMO-USER-TYPE" -> Optional.of(conversation.user().type().code);
            case "CDEMO-CUnn-USRID-FIRST" -> Optional.of(page.map(UserListPage::firstId).orElse(""));
            case "CDEMO-CUnn-USRID-LAST" -> Optional.of(page.map(UserListPage::lastId).orElse(""));
            case "CDEMO-CUnn-PAGE-NUM" -> Optional.of(page.map(p -> String.valueOf(p.pageNumber())).orElse(""));
            case "CDEMO-CUnn-NEXT-PAGE-FLG" -> Optional.of(page.map(p -> p.hasNext() ? "Y" : "N").orElse(""));
            case "CDEMO-CUnn-USR-SEL-FLG" -> Optional.of(selection.map(s -> s.action().name().substring(0, 1)).orElse(""));
            case "CDEMO-CUnn-USR-SELECTED" -> Optional.of(selection.map(UserSelection::userId).orElse(""));
            default -> throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
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

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private static Map<String, Object> yaml(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
