package com.carddemo.online;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * COMEN01C and COADM01C: a table of numbered options, each an XCTL to its program. The screen lists
 * only installed options the user may run, keeping their legacy numbers.
 */
public class MenuProgram implements ScreenProgram {

    private final Program program;
    private final List<MenuOption> options;
    private final Function<MenuOption, Message> notInstalled;
    private final Predicate<Program> installed;

    public MenuProgram(Program program, List<MenuOption> options, Function<MenuOption, Message> notInstalled,
                       Predicate<Program> installed) {
        this.program = program;
        this.options = options;
        this.notInstalled = notInstalled;
        this.installed = installed;
    }

    @Override
    public Program program() {
        return program;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return show(conversation, "", null);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        return switch (aid) {
            case ENTER -> choose(conversation, option(fields.getOrDefault("OPTION", "")));
            case PF3 -> new Outcome.SignOff(null);
            default -> show(conversation, fields.getOrDefault("OPTION", ""), Message.INVALID_KEY);
        };
    }

    private Outcome choose(Conversation conversation, String option) {
        Optional<MenuOption> chosen = options.stream().filter(o -> number(o).equals(option)).findFirst();
        if (chosen.isEmpty()) {
            return show(conversation, option, Message.error("Please enter a valid option number..."));
        }
        if (!permits(conversation, chosen.get())) {
            return show(conversation, option, Message.error("No access - Admin Only option... "));
        }
        if (!installed.test(chosen.get().target())) {
            return show(conversation, option, notInstalled.apply(chosen.get()));
        }
        return new Outcome.Transfer(chosen.get().target());
    }

    private Outcome show(Conversation conversation, String option, Message message) {
        Map<String, String> fields = new HashMap<>();
        fields.put("OPTION", option);
        List<MenuOption> visible = options.stream()
                .filter(o -> installed.test(o.target()) && permits(conversation, o)).toList();
        for (int i = 0; i < visible.size(); i++) {
            fields.put("OPTN%03d".formatted(i + 1), number(visible.get(i)) + ". " + visible.get(i).name());
        }
        return new Outcome.Show(fields, message, "OPTION");
    }

    private static boolean permits(Conversation conversation, MenuOption option) {
        return option.access() == UserType.USER || conversation.user().type() == UserType.ADMIN;
    }

    private static String number(MenuOption option) {
        return "%02d".formatted(option.number());
    }

    /** OPTION is X(02) JUST RIGHT with spaces turned to zeros, so '1', ' 1' and '01' are the same. */
    static String option(String typed) {
        return typed.length() > 2 ? typed : "%2s".formatted(typed).replace(' ', '0');
    }
}
