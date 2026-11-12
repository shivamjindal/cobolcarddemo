package com.carddemo.trantypes;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import com.carddemo.trantypes.TranTypeForm.Phase;
import com.carddemo.trantypes.TranTypeRepository.DeleteOutcome;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * COTRTUPC. Search for a type, edit its description, and confirm with F5. F4 deletes after a confirmation.
 * F5 on a code that is not on file starts an add. F12 cancels the open action. PF3 returns to the caller.
 */
@Component
class TranTypeMaintenance implements ScreenProgram {

    static final String SEARCH = "Enter transaction type to be maintained";
    static final String ADD = "Press F05 to add. F12 to cancel";
    static final String NEW_DATA = "Enter new transaction type details.";
    static final String CONFIRM = "Changes validated.Press F5 to save";
    static final String SAVED = "Changes committed to database";
    static final String DELETE_ASK = "Delete this record ? Press F4 to confirm";
    static final String DELETED = "Delete successful.";
    static final String CHANGES = "Update transaction type details shown.";
    static final String FAILED = "Changes unsuccessful";
    static final String NOT_FOUND = "No record found for this key in database";
    static final String NO_CHANGE = "No change detected with respect to values fetched.";
    static final String INVALID_KEY = "Invalid key pressed";

    private final TranTypeRepository types;

    TranTypeMaintenance(TranTypeRepository types) {
        this.types = types;
    }

    @Override
    public Program program() {
        return Program.COTRTUPC;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return show(conversation, TranTypeForm.fresh(), "", "", null, SEARCH, "TRTYPCD", false);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        if (aid == Aid.PF3) {
            return new Outcome.Transfer(conversation.from().orElse(Program.COADM01C));
        }
        TranTypeForm form = conversation.get(TranTypeForm.class).orElseGet(TranTypeForm::fresh);
        if (form.phase().ended() || (aid == Aid.PF12 && resets(form.phase()))) {
            return show(conversation, TranTypeForm.fresh(), "", "", null, SEARCH, "TRTYPCD", false);
        }
        if (!valid(aid, form.phase())) {
            return show(conversation, form, displayedCode(form), displayedDesc(form, false),
                    Message.error(INVALID_KEY), info(form.phase()), cursor(form), locked(form.phase()));
        }
        return switch (form.phase()) {
            case NOT_FETCHED -> search(conversation, fields);
            case NOT_FOUND -> aid == Aid.PF5
                    ? show(conversation, form.phase(Phase.CREATE), form.code(), "", null, NEW_DATA, "TRTYDSC", false)
                    : search(conversation, fields);
            case SHOWN, INVALID_EDIT, BACKED_OUT -> aid == Aid.PF4 && form.phase() == Phase.SHOWN
                    ? show(conversation, form.phase(Phase.CONFIRM_DELETE), form.code(), form.description(), null,
                            DELETE_ASK, "TRTYPCD", true)
                    : edit(conversation, form, fields);
            case CREATE -> edit(conversation, form, fields);
            case CONFIRM -> aid == Aid.PF5 ? write(conversation, form)
                    : aid == Aid.PF12 ? cancelUpdate(conversation, form) : show(conversation, form, form.code(),
                            form.edited(), null, CONFIRM, "TRTYDSC", true);
            case CONFIRM_DELETE -> aid == Aid.PF4 ? remove(conversation, form)
                    : aid == Aid.PF12 ? show(conversation, TranTypeForm.fresh(), "*", "",
                            Message.error("Delete was cancelled"), SEARCH, "TRTYPCD", false)
                    : show(conversation, form, form.code(), form.description(), Message.error(INVALID_KEY),
                            DELETE_ASK, "TRTYPCD", true);
            default -> show(conversation, TranTypeForm.fresh(), "", "", null, SEARCH, "TRTYPCD", false);
        };
    }

    private Outcome search(Conversation conversation, Map<String, String> fields) {
        String typed = fields.getOrDefault("TRTYPCD", "").trim();
        if (typed.isEmpty() || "*".equals(typed)) {
            return show(conversation, TranTypeForm.fresh(), "*", "",
                    Message.error("Tran Type code must be supplied."), SEARCH, "TRTYPCD", false);
        }
        if (!typed.chars().allMatch(Character::isDigit)) {
            return show(conversation, TranTypeForm.fresh(), typed, "",
                    Message.error("Tran Type code must be numeric."), SEARCH, "TRTYPCD", false);
        }
        if (Integer.parseInt(typed) == 0) {
            return show(conversation, TranTypeForm.fresh(), typed, "",
                    Message.error("Tran Type code must not be zero."), SEARCH, "TRTYPCD", false);
        }
        String code = "%02d".formatted(Integer.parseInt(typed));
        try {
            Optional<TranType> found = types.find(code);
            if (found.isEmpty()) {
                TranTypeForm form = new TranTypeForm(Phase.NOT_FOUND, code, "", "");
                return show(conversation, form, code, "", Message.error(NOT_FOUND), ADD, "TRTYPCD", false);
            }
            TranType row = found.get();
            TranTypeForm form = new TranTypeForm(Phase.SHOWN, row.code(), row.description(), row.description());
            return show(conversation, form, row.code(), row.description(), null, SEARCH, "TRTYDSC", false);
        } catch (DataAccessException e) {
            return show(conversation, TranTypeForm.fresh(), code, "",
                    Message.error("Error accessing: TRANSACTION_TYPE table. SQLCODE:" + e.getClass().getSimpleName()),
                    SEARCH, "TRTYPCD", false);
        }
    }

    private Outcome edit(Conversation conversation, TranTypeForm form, Map<String, String> fields) {
        String description = fields.getOrDefault("TRTYDSC", "");
        if (form.phase() != Phase.CREATE && Descriptions.unchanged(description, form.description())) {
            return show(conversation, form.phase(Phase.SHOWN), form.code(), form.description(),
                    Message.error(NO_CHANGE), SEARCH, "TRTYDSC", false);
        }
        String problem = Descriptions.problem(description);
        if (problem != null) {
            TranTypeForm invalid = new TranTypeForm(Phase.INVALID_EDIT, form.code(), form.description(), description);
            String info = form.phase() == Phase.CREATE ? NEW_DATA : CHANGES;
            return show(conversation, invalid, form.code(), description, Message.error(problem), info, "TRTYDSC", false);
        }
        TranTypeForm confirm = new TranTypeForm(Phase.CONFIRM, form.code(), form.description(),
                Descriptions.stored(description));
        return show(conversation, confirm, form.code(), confirm.edited(), null, CONFIRM, "TRTYDSC", true);
    }

    private Outcome write(Conversation conversation, TranTypeForm form) {
        TranType row = new TranType(form.code(), Descriptions.stored(form.edited()));
        try {
            if (!types.update(row)) {
                types.insert(row);
            }
            TranTypeForm saved = new TranTypeForm(Phase.SAVED, row.code(), row.description(), row.description());
            return show(conversation, saved, row.code(), row.description(), null, SAVED, "TRTYPCD", true);
        } catch (DuplicateKeyException e) {
            return failed(conversation, form, "Error inserting record into: TRANSACTION_TYPE Table. SQLCODE:-803");
        } catch (DataAccessException e) {
            return failed(conversation, form,
                    "Error updating: TRANSACTION_TYPE Table. SQLCODE:" + e.getClass().getSimpleName());
        }
    }

    /** A failed write is not reported as committed. The legacy second EVALUATE ran after the message had been overwritten. */
    private Outcome failed(Conversation conversation, TranTypeForm form, String message) {
        return show(conversation, new TranTypeForm(Phase.DELETE_FAILED, form.code(), form.description(), form.edited()),
                form.code(), form.edited(), Message.error(message), FAILED, "TRTYPCD", true);
    }

    private Outcome remove(Conversation conversation, TranTypeForm form) {
        try {
            return switch (types.delete(form.code())) {
                case DELETED -> show(conversation, new TranTypeForm(Phase.DELETE_DONE, form.code(), form.description(),
                        form.description()), form.code(), form.description(), null, DELETED, "TRTYPCD", true);
                case RESTRICTED -> show(conversation, form, form.code(), form.description(),
                        Message.error("Please delete associated child records first:SQLCODE : -532"), SEARCH,
                        "TRTYPCD", true);
                case MISSING -> show(conversation, new TranTypeForm(Phase.DELETE_FAILED, form.code(),
                        form.description(), form.description()), form.code(), form.description(),
                        Message.error("Delete failed with message:SQLCODE :  100"), FAILED, "TRTYPCD", true);
            };
        } catch (DataAccessException e) {
            return show(conversation, new TranTypeForm(Phase.DELETE_FAILED, form.code(), form.description(),
                    form.description()), form.code(), form.description(),
                    Message.error("Delete failed with message:SQLCODE :" + e.getClass().getSimpleName()), FAILED,
                    "TRTYPCD", true);
        }
    }

    private Outcome cancelUpdate(Conversation conversation, TranTypeForm form) {
        TranTypeForm backed = new TranTypeForm(Phase.BACKED_OUT, form.code(), form.description(), form.description());
        return show(conversation, backed, form.code(), form.description(), Message.error("Update was cancelled"),
                CHANGES, "TRTYDSC", false);
    }

    private static boolean resets(Phase phase) {
        return phase == Phase.SHOWN || phase == Phase.CREATE || phase == Phase.NOT_FOUND;
    }

    private static boolean valid(Aid aid, Phase phase) {
        return switch (aid) {
            case ENTER -> phase != Phase.CONFIRM_DELETE;
            case PF4 -> phase == Phase.SHOWN || phase == Phase.CONFIRM_DELETE;
            case PF5 -> phase == Phase.CONFIRM || phase == Phase.NOT_FOUND;
            case PF12 -> phase == Phase.CONFIRM || phase == Phase.SHOWN || phase == Phase.NOT_FOUND
                    || phase == Phase.CONFIRM_DELETE || phase == Phase.CREATE;
            default -> false;
        };
    }

    private static String info(Phase phase) {
        return switch (phase) {
            case NOT_FOUND -> ADD;
            case CREATE -> NEW_DATA;
            case CONFIRM -> CONFIRM;
            case CONFIRM_DELETE -> DELETE_ASK;
            case DELETE_DONE, SAVED -> phase == Phase.DELETE_DONE ? DELETED : SAVED;
            case INVALID_EDIT, BACKED_OUT -> CHANGES;
            default -> SEARCH;
        };
    }

    private static String cursor(TranTypeForm form) {
        return switch (form.phase()) {
            case SHOWN, CREATE, CONFIRM, INVALID_EDIT, BACKED_OUT -> "TRTYDSC";
            default -> "TRTYPCD";
        };
    }

    private static boolean locked(Phase phase) {
        return phase == Phase.CONFIRM || phase == Phase.CONFIRM_DELETE || phase == Phase.SAVED
                || phase == Phase.DELETE_DONE || phase == Phase.DELETE_FAILED;
    }

    private static String displayedCode(TranTypeForm form) {
        return form.code();
    }

    private static String displayedDesc(TranTypeForm form, boolean edited) {
        return edited ? form.edited() : form.description();
    }

    private Outcome show(Conversation conversation, TranTypeForm form, String code, String description, Message message,
                         String info, String cursor, boolean locked) {
        conversation.put(form);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("TRTYPCD", code == null ? "" : code);
        fields.put("TRTYDSC", description == null ? "" : description);
        fields.put("INFOMSG", info);
        Set<String> readOnly = locked ? Set.of("TRTYPCD", "TRTYDSC") : Set.of("TRTYPCD");
        if (form.phase() == Phase.NOT_FETCHED || form.phase() == Phase.NOT_FOUND) {
            readOnly = Set.of();
        }
        if (form.phase() == Phase.CREATE || form.phase() == Phase.SHOWN || form.phase() == Phase.INVALID_EDIT
                || form.phase() == Phase.BACKED_OUT) {
            readOnly = Set.of("TRTYPCD");
        }
        return new Outcome.Show(fields, message, cursor, readOnly);
    }
}
