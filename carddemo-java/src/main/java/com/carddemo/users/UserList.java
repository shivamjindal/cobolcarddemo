package com.carddemo.users;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** COUSR00C: ten users a page in ID order, a start-from filter, and U/D row selection. */
@Component
class UserList implements ScreenProgram {

    private static final int ROWS = 10;

    private final UserRepository users;

    UserList(UserRepository users) {
        this.users = users;
    }

    @Override
    public Program program() {
        return Program.COUSR00C;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        conversation.take(UserSelection.class);
        return page(conversation, users.pageFrom("", ROWS + 1), 1, null);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        UserListPage current = conversation.get(UserListPage.class).orElse(UserListPage.FIRST);
        return switch (aid) {
            case ENTER -> enter(conversation, fields);
            case PF3 -> new Outcome.Transfer(Program.COADM01C);
            case PF7 -> backward(conversation, current);
            case PF8 -> forward(conversation, current);
            default -> again(conversation, current, Message.INVALID_KEY);
        };
    }

    /** The first marked row wins; ENTER otherwise restarts at page 1 from the filter. */
    private Outcome enter(Conversation conversation, Map<String, String> fields) {
        for (int row = 1; row <= ROWS; row++) {
            String mark = fields.getOrDefault("SEL%04d".formatted(row), "");
            if (mark.isBlank()) {
                continue;
            }
            String id = Credentials.normalize(fields.getOrDefault("USRID%02d".formatted(row), ""));
            if (mark.equalsIgnoreCase("U")) {
                conversation.put(new UserSelection(id, UserSelection.Action.UPDATE));
                return new Outcome.Transfer(Program.COUSR02C);
            }
            if (mark.equalsIgnoreCase("D")) {
                conversation.put(new UserSelection(id, UserSelection.Action.DELETE));
                return new Outcome.Transfer(Program.COUSR03C);
            }
            return filtered(conversation, fields, Message.error("Invalid selection. Valid values are U and D"));
        }
        return filtered(conversation, fields, null);
    }

    private Outcome filtered(Conversation conversation, Map<String, String> fields, Message message) {
        String start = Credentials.normalize(fields.getOrDefault("USRIDIN", ""));
        return page(conversation, users.pageFrom(start, ROWS + 1), 1, message);
    }

    private Outcome backward(Conversation conversation, UserListPage current) {
        if (current.pageNumber() <= 1) {
            return again(conversation, current, Message.info("You are already at the top of the page..."));
        }
        List<SecUser> before = users.pageBefore(current.firstId(), ROWS + 1);
        if (before.size() > ROWS) {
            String start = before.get(ROWS - 1).id();
            return page(conversation, users.pageFrom(start, ROWS + 1), current.pageNumber() - 1, null);
        }
        return page(conversation, users.pageFrom("", ROWS + 1), 1,
                Message.info("You have reached the top of the page..."));
    }

    private Outcome forward(Conversation conversation, UserListPage current) {
        if (!current.hasNext()) {
            return again(conversation, current, Message.info("You are already at the bottom of the page..."));
        }
        return page(conversation, users.pageAfter(current.lastId(), ROWS + 1), current.pageNumber() + 1, null);
    }

    private Outcome again(Conversation conversation, UserListPage current, Message message) {
        return page(conversation, users.pageFrom(current.firstId(), ROWS + 1), current.pageNumber(), message);
    }

    /** Rows holds up to one user past the page, which tells whether PF8 has a next page. */
    private Outcome page(Conversation conversation, List<SecUser> rows, int pageNumber, Message message) {
        List<SecUser> shown = rows.subList(0, Math.min(ROWS, rows.size()));
        boolean hasNext = rows.size() > ROWS;
        conversation.put(shown.isEmpty() ? new UserListPage("", "", pageNumber, false)
                : new UserListPage(shown.getFirst().id(), shown.getLast().id(), pageNumber, hasNext));
        Map<String, String> fields = new HashMap<>();
        fields.put("PAGENUM", "%08d".formatted(pageNumber));
        fields.put("USRIDIN", "");
        for (int i = 0; i < shown.size(); i++) {
            String row = "%02d".formatted(i + 1);
            SecUser user = shown.get(i);
            fields.put("USRID" + row, user.id());
            fields.put("FNAME" + row, user.firstName());
            fields.put("LNAME" + row, user.lastName());
            fields.put("UTYPE" + row, user.type().code);
        }
        if (message == null && shown.isEmpty()) {
            message = Message.info("You are at the top of the page...");
        } else if (message == null && !hasNext) {
            message = Message.info("You have reached the bottom of the page...");
        }
        return new Outcome.Show(fields, message, "USRIDIN");
    }
}
