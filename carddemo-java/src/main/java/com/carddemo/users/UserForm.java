package com.carddemo.users;

import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import org.springframework.dao.DataAccessException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** What the add, update and delete screens share: required-field checks, echo and lookup. */
final class UserForm {

    private static final Map<String, String> LABELS = Map.of("USRIDIN", "User ID", "USERID", "User ID",
            "FNAME", "First Name", "LNAME", "Last Name", "PASSWD", "Password", "USRTYPE", "User Type");

    private UserForm() {
    }

    /** The first blank field in screen order, as the legacy programs check them. */
    static Optional<Outcome.Show> missing(Map<String, String> fields, String... required) {
        return Arrays.stream(required).filter(name -> fields.getOrDefault(name, "").isBlank()).findFirst()
                .map(name -> new Outcome.Show(echo(fields, required),
                        Message.error(LABELS.get(name) + " can NOT be empty..."), name));
    }

    /** Copies the named input fields back to the screen; a password is never sent back. */
    static Map<String, String> echo(Map<String, String> fields, String... names) {
        Map<String, String> echoed = new HashMap<>();
        Arrays.stream(names).filter(name -> !name.equals("PASSWD") && fields.containsKey(name))
                .forEach(name -> echoed.put(name, fields.get(name)));
        return echoed;
    }

    /** ENTER on COUSR02C and COUSR03C, and arriving from the list with a selected row. */
    static Outcome.Show lookup(UserRepository users, String typedId, String prompt) {
        if (typedId.isBlank()) {
            return emptyId();
        }
        String id = Credentials.normalize(typedId);
        try {
            return users.find(id)
                    .map(user -> new Outcome.Show(Map.of("USRIDIN", user.id(), "FNAME", user.firstName(),
                            "LNAME", user.lastName(), "USRTYPE", user.type().code), Message.info(prompt), "USRIDIN"))
                    .orElseGet(() -> notFound(id));
        } catch (DataAccessException e) {
            return new Outcome.Show(Map.of("USRIDIN", id), Message.error("Unable to lookup User..."), "USRIDIN");
        }
    }

    static Outcome.Show emptyId() {
        return new Outcome.Show(Map.of(), Message.error("User ID can NOT be empty..."), "USRIDIN");
    }

    static Outcome.Show notFound(String id) {
        return new Outcome.Show(Map.of("USRIDIN", id), Message.error("User ID NOT found..."), "USRIDIN");
    }
}
