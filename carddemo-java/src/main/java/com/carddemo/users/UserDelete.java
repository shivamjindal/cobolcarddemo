package com.carddemo.users;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Map;

/** COUSR03C. */
@Component
class UserDelete implements ScreenProgram {

    private static final String PROMPT = "Press PF5 key to delete this user ...";

    private final UserRepository users;

    UserDelete(UserRepository users) {
        this.users = users;
    }

    @Override
    public Program program() {
        return Program.COUSR03C;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return conversation.take(UserSelection.class)
                .<Outcome>map(selected -> UserForm.lookup(users, selected.userId(), PROMPT))
                .orElseGet(UserDelete::blank);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        String typedId = fields.getOrDefault("USRIDIN", "");
        return switch (aid) {
            case ENTER -> UserForm.lookup(users, typedId, PROMPT);
            case PF3 -> new Outcome.Transfer(conversation.from().orElse(Program.COADM01C));
            case PF4 -> blank();
            case PF5 -> delete(typedId);
            case PF12 -> new Outcome.Transfer(Program.COADM01C);
            default -> new Outcome.Show(UserForm.echo(fields, "USRIDIN"), Message.INVALID_KEY, "USRIDIN");
        };
    }

    private static Outcome blank() {
        return new Outcome.Show(Map.of(), null, "USRIDIN");
    }

    private Outcome delete(String typedId) {
        if (typedId.isBlank()) {
            return UserForm.emptyId();
        }
        String id = Credentials.normalize(typedId);
        try {
            if (!users.delete(id)) {
                return UserForm.notFound(id);
            }
            return new Outcome.Show(Map.of(), Message.success("User " + id + " has been deleted ..."), "USRIDIN");
        } catch (DataAccessException e) {
            return new Outcome.Show(Map.of("USRIDIN", id), Message.error("Unable to Update User..."), "USRIDIN");
        }
    }
}
