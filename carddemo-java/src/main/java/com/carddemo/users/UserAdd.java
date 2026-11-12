package com.carddemo.users;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import com.carddemo.online.UserType;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** COUSR01C. */
@Component
class UserAdd implements ScreenProgram {

    private static final String[] FIELDS = {"FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE"};

    private final UserRepository users;

    UserAdd(UserRepository users) {
        this.users = users;
    }

    @Override
    public Program program() {
        return Program.COUSR01C;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return new Outcome.Show(Map.of(), null, "FNAME");
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        return switch (aid) {
            case ENTER -> add(fields);
            case PF3 -> new Outcome.Transfer(Program.COADM01C);
            case PF4 -> arrive(conversation);
            default -> redisplay(fields, Message.INVALID_KEY);
        };
    }

    private Outcome add(Map<String, String> fields) {
        Optional<Outcome.Show> missing = UserForm.missing(fields, FIELDS);
        if (missing.isPresent()) {
            return missing.get();
        }
        Optional<UserType> type = UserType.typed(fields.get("USRTYPE"));
        if (type.isEmpty()) {
            return new Outcome.Show(UserForm.echo(fields, FIELDS), Message.error("User Type must be A or U..."), "USRTYPE");
        }
        String id = Credentials.normalize(fields.get("USERID"));
        try {
            users.insert(new SecUser(id, fields.get("FNAME"), fields.get("LNAME"),
                    Credentials.hash(fields.get("PASSWD")), type.get()));
            return new Outcome.Show(Map.of(), Message.success("User " + id + " has been added ..."), "FNAME");
        } catch (DuplicateKeyException e) {
            // COUSR01C aims the cursor at User ID, but BMS puts it on the first flagged field, First Name.
            return redisplay(fields, Message.error("User ID already exist..."));
        } catch (DataAccessException e) {
            return redisplay(fields, Message.error("Unable to Add User..."));
        }
    }

    private Outcome redisplay(Map<String, String> fields, Message message) {
        return new Outcome.Show(UserForm.echo(fields, FIELDS), message, "FNAME");
    }
}
