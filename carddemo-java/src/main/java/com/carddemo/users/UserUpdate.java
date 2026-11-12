package com.carddemo.users;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import com.carddemo.online.UserType;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * COUSR02C. The password field is blank on load because only its hash is stored, so a blank
 * password on save keeps the current one.
 */
@Component
class UserUpdate implements ScreenProgram {

    private static final String[] FIELDS = {"USRIDIN", "FNAME", "LNAME", "PASSWD", "USRTYPE"};
    private static final String PROMPT = "Press PF5 key to save your updates ...";

    private final UserRepository users;

    UserUpdate(UserRepository users) {
        this.users = users;
    }

    @Override
    public Program program() {
        return Program.COUSR02C;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return conversation.take(UserSelection.class)
                .<Outcome>map(selected -> UserForm.lookup(users, selected.userId(), PROMPT))
                .orElseGet(UserUpdate::blank);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        return switch (aid) {
            case ENTER -> UserForm.lookup(users, fields.getOrDefault("USRIDIN", ""), PROMPT);
            case PF3 -> {
                save(fields);
                yield new Outcome.Transfer(conversation.from().orElse(Program.COADM01C));
            }
            case PF4 -> blank();
            case PF5 -> save(fields);
            case PF12 -> new Outcome.Transfer(Program.COADM01C);
            default -> new Outcome.Show(UserForm.echo(fields, FIELDS), Message.INVALID_KEY, "USRIDIN");
        };
    }

    private static Outcome blank() {
        return new Outcome.Show(Map.of(), null, "USRIDIN");
    }

    private Outcome save(Map<String, String> fields) {
        Optional<Outcome.Show> missing = UserForm.missing(fields, "USRIDIN", "FNAME", "LNAME", "USRTYPE");
        if (missing.isPresent()) {
            return missing.get();
        }
        Map<String, String> echo = UserForm.echo(fields, FIELDS);
        Optional<UserType> type = UserType.typed(fields.get("USRTYPE"));
        if (type.isEmpty()) {
            return new Outcome.Show(echo, Message.error("User Type must be A or U..."), "USRTYPE");
        }
        String id = Credentials.normalize(fields.get("USRIDIN"));
        try {
            Optional<SecUser> current = users.find(id);
            if (current.isEmpty()) {
                return UserForm.notFound(id);
            }
            String password = fields.getOrDefault("PASSWD", "");
            String hash = password.isBlank() || Credentials.matches(password, current.get().passwordHash())
                    ? current.get().passwordHash() : Credentials.hash(password);
            SecUser updated = new SecUser(id, fields.get("FNAME"), fields.get("LNAME"), hash, type.get());
            if (updated.equals(current.get())) {
                return new Outcome.Show(echo, Message.error("Please modify to update ..."), "FNAME");
            }
            users.update(updated);
            return new Outcome.Show(echo, Message.success("User " + id + " has been updated ..."), "FNAME");
        } catch (DataAccessException e) {
            return new Outcome.Show(echo, Message.error("Unable to Update User..."), "FNAME");
        }
    }
}
