package com.carddemo.trantypes;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import com.carddemo.trantypes.TranTypeListState.Slot;
import com.carddemo.trantypes.TranTypeRepository.DeleteOutcome;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * COTRTLIC. Seven types a page, a type filter and a description filter, and an inline U or D that F10 confirms.
 * Any key other than PF2, PF3, PF7, PF8 and a confirming F10 is treated as ENTER.
 */
@Component
class TranTypeList implements ScreenProgram {

    static final int ROWS = 7;
    static final String ACTIONS = "Type U to update, D to delete any record";
    static final String CONFIRM_DELETE = "Delete HIGHLIGHTED row ? Press F10 to confirm";
    static final String CONFIRM_UPDATE = "Update HIGHLIGHTED row. Press F10 to save";
    static final String DELETED = "HIGHLIGHTED row deleted.Hit Enter to continue";
    static final String UPDATED = "HIGHLIGHTED row was updated";
    static final String NO_ROWS = "No records found for this search condition.";
    static final String NO_MORE = "No more pages for these search conditions";
    static final String NO_MATCH = "No Records found for these filter conditions";

    private final TranTypeRepository types;

    TranTypeList(TranTypeRepository types) {
        this.types = types;
    }

    @Override
    public Program program() {
        return Program.COTRTLIC;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return forward(conversation, TranTypeListState.fresh(), "", 1, null, false);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        if (aid == Aid.PF3) {
            return new Outcome.Transfer(Program.COADM01C);
        }
        if (aid == Aid.PF2) {
            return new Outcome.Transfer(Program.COTRTUPC);
        }
        TranTypeListState state = conversation.get(TranTypeListState.class).orElseGet(TranTypeListState::fresh);
        Input input = Input.read(fields, state);
        Aid key = aid == Aid.PF7 || aid == Aid.PF8 || (aid == Aid.PF10 && input.confirmable(state)) ? aid : Aid.ENTER;
        if (input.typeError() != null) {
            return render(conversation, state.filtered(input.typeFilter(), input.descFilter()), input,
                    Message.error(input.typeError()), null, "TRTYPE", true);
        }
        if (input.filterActive() && noneMatch(input)) {
            return render(conversation, state.filtered(input.typeFilter(), input.descFilter()), input,
                    Message.error(NO_MATCH), null, input.typeFilter().isBlank() ? "TRDESC" : "TRTYPE", true);
        }
        TranTypeListState filtered = input.filterChanged()
                ? state.filtered(input.typeFilter(), input.descFilter()) : state;
        if (key == Aid.PF7 && filtered.page() <= 1) {
            return forward(conversation, filtered, filtered.firstCode(), filtered.page(),
                    Message.error("No previous pages to display"), false);
        }
        if (key == Aid.PF8 && filtered.nextPage()) {
            return forward(conversation, filtered, filtered.anchor(), filtered.page() + 1, null, true);
        }
        if (key == Aid.PF8 && filtered.lastPageShown()) {
            return forward(conversation, filtered, filtered.firstCode(), filtered.page(),
                    Message.error("No more pages to display"), true);
        }
        if (key == Aid.PF8) {
            return forward(conversation, filtered, filtered.firstCode(), filtered.page(), null, true);
        }
        if (key == Aid.PF7) {
            return backward(conversation, filtered);
        }
        if (key == Aid.PF10 && input.deletes() == 1) {
            return delete(conversation, filtered, input);
        }
        if (key == Aid.PF10 && input.updates() == 1) {
            return save(conversation, filtered, input);
        }
        if (input.markError() != null) {
            return render(conversation, filtered, input, Message.error(input.markError()), ACTIONS, input.cursor(), false);
        }
        if (input.deletes() == 1) {
            return render(conversation, filtered.pending(true, false, input.selected()), input, null, CONFIRM_DELETE,
                    "TRTSEL" + input.selected(), false);
        }
        if (input.updates() == 1 && input.unchanged()) {
            return render(conversation, filtered, input,
                    Message.error("No change detected with respect to database values."), ACTIONS,
                    "TRTYPD" + input.selected(), false);
        }
        if (input.updates() == 1 && input.editError() != null) {
            return render(conversation, filtered, input, Message.error(input.editError()), ACTIONS,
                    "TRTYPD" + input.selected(), false);
        }
        if (input.updates() == 1) {
            return render(conversation, filtered.pending(false, true, input.selected()), input, null, CONFIRM_UPDATE,
                    "TRTYPD" + input.selected(), false);
        }
        String start = input.filterChanged() || filtered.firstCode().isEmpty() ? "" : filtered.firstCode();
        return forward(conversation, filtered, start, input.filterChanged() ? 1 : filtered.page(), null, false);
    }

    private boolean noneMatch(Input input) {
        try {
            return types.count(input.typeFilter(), like(input.descFilter())) == 0;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private Outcome forward(Conversation conversation, TranTypeListState state, String start, int page,
                            Message forced, boolean fromPf8) {
        try {
            List<TranType> rows = types.forward(start == null ? "" : start, state.typeFilter(),
                    like(state.descFilter()), ROWS + 1);
            boolean more = rows.size() > ROWS;
            List<TranType> shown = more ? rows.subList(0, ROWS) : rows;
            String first = shown.isEmpty() ? "" : shown.getFirst().code();
            String anchor = more ? rows.get(ROWS).code() : (shown.isEmpty() ? "" : shown.getLast().code());
            List<Slot> slots = new ArrayList<>();
            for (int i = 0; i < shown.size(); i++) {
                slots.add(new Slot(i + 1, shown.get(i).code(), shown.get(i).description()));
            }
            boolean noteLast = fromPf8 && !more;
            TranTypeListState pageState = new TranTypeListState(state.typeFilter(), state.descFilter(),
                    page, first, anchor, more, state.lastPageShown() || noteLast, slots,
                    0, false, false);
            Message error = forced;
            String info = ACTIONS;
            if (shown.isEmpty() && page <= 1 && forced == null) {
                error = Message.error(NO_ROWS);
                info = null;
            } else if (fromPf8 && !more && forced == null) {
                error = Message.error(state.lastPageShown() ? "No more pages to display" : NO_MORE);
            }
            return render(conversation, pageState, Input.echo(pageState), error, info, "TRTYPE", false);
        } catch (DataAccessException e) {
            return render(conversation, state, Input.echo(state),
                    Message.error("C-TR-TYPE-FORWARD Open SQLCODE: " + e.getClass().getSimpleName()), null, "TRTYPE",
                    false);
        }
    }

    private Outcome backward(Conversation conversation, TranTypeListState state) {
        try {
            List<TranType> rows = types.backward(state.firstCode(), state.typeFilter(), like(state.descFilter()), ROWS);
            if (rows.size() < ROWS) {
                List<Slot> slots = new ArrayList<>();
                int screen = ROWS;
                for (TranType row : rows) {
                    slots.add(0, new Slot(screen--, row.code(), row.description()));
                }
                TranTypeListState page = new TranTypeListState(state.typeFilter(), state.descFilter(), state.page(),
                        state.firstCode(), state.anchor(), true, false, slots, 0, false, false);
                return render(conversation, page, Input.echo(page),
                        Message.error("Error on fetch Cursor C-TR-TYPE-BACKWARD SQLCODE:  100"), null, "TRTYPE", false);
            }
            List<Slot> slots = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                TranType row = rows.get(rows.size() - 1 - i);
                slots.add(new Slot(i + 1, row.code(), row.description()));
            }
            TranTypeListState page = new TranTypeListState(state.typeFilter(), state.descFilter(), state.page() - 1,
                    slots.getFirst().code(), slots.getLast().code(), true, false, slots, 0, false, false);
            return render(conversation, page, Input.echo(page), null, ACTIONS, "TRTYPE", false);
        } catch (DataAccessException e) {
            return render(conversation, state, Input.echo(state),
                    Message.error("C-TR-TYPE-BACKWARD Open SQLCODE: " + e.getClass().getSimpleName()), null, "TRTYPE",
                    false);
        }
    }

    private Outcome delete(Conversation conversation, TranTypeListState state, Input input) {
        try {
            return switch (types.delete(input.slot().code())) {
                case DELETED -> deleted(conversation);
                case RESTRICTED -> render(conversation, state.pending(true, false, input.selected()), input,
                        Message.error("Please delete associated child records first: SQLCODE: -532"), ACTIONS,
                        "TRTSEL" + input.selected(), false);
                case MISSING -> render(conversation, state, input,
                        Message.error("Delete failed with message: SQLCODE:  100"), ACTIONS,
                        "TRTSEL" + input.selected(), false);
            };
        } catch (DataAccessException e) {
            return render(conversation, state, input,
                    Message.error("Delete failed with message: SQLCODE: " + e.getClass().getSimpleName()), ACTIONS,
                    "TRTSEL" + input.selected(), false);
        }
    }

    private Outcome deleted(Conversation conversation) {
        Outcome shown = forward(conversation, TranTypeListState.fresh(), "", 1, null, false);
        if (shown instanceof Outcome.Show show) {
            Map<String, String> fields = new LinkedHashMap<>(show.fields());
            fields.put("INFOMSG", DELETED);
            return new Outcome.Show(fields, null, "TRTYPE", show.readOnly());
        }
        return shown;
    }

    private Outcome save(Conversation conversation, TranTypeListState state, Input input) {
        if (input.editError() != null) {
            return render(conversation, state, input, Message.error(input.editError()), ACTIONS,
                    "TRTYPD" + input.selected(), false);
        }
        String description = Descriptions.stored(input.edited());
        try {
            if (!types.update(new TranType(input.slot().code(), description))) {
                return render(conversation, state, input,
                        Message.error("Record not found. Deleted by others ? SQLCODE:  100"), ACTIONS,
                        "TRTYPD" + input.selected(), false);
            }
        } catch (DataAccessException e) {
            return render(conversation, state, input,
                    Message.error("Update failed with SQLCODE: " + e.getClass().getSimpleName()), ACTIONS,
                    "TRTYPD" + input.selected(), false);
        }
        String start = state.page() <= 1 ? "" : state.firstCode();
        Outcome shown = forward(conversation, state, start, state.page(), null, false);
        if (shown instanceof Outcome.Show show) {
            Map<String, String> fields = new LinkedHashMap<>(show.fields());
            fields.put("INFOMSG", UPDATED);
            return new Outcome.Show(fields, null, "TRTYPE", show.readOnly());
        }
        return shown;
    }

    private Outcome render(Conversation conversation, TranTypeListState state, Input input, Message message,
                           String info, String cursor, boolean protectRows) {
        conversation.put(state);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("PAGENO", Integer.toString(Math.max(state.page(), 1)));
        fields.put("TRTYPE", input.typeText() == null ? "" : input.typeText());
        fields.put("TRDESC", input.descText() == null ? "" : input.descText());
        if (info != null && (message == null || !NO_ROWS.equals(message.text()))) {
            fields.put("INFOMSG", info);
        }
        Set<String> readOnly = new HashSet<>();
        for (int row = 1; row <= ROWS; row++) {
            readOnly.add("TRTTYP" + row);
        }
        for (Slot slot : state.slots()) {
            fields.put("TRTTYP" + slot.row(), slot.code());
            String description = slot.description();
            String mark = input.selected() == slot.row() ? input.mark() : "";
            if ("U".equals(mark) && input.edited() != null && !input.unchanged()) {
                description = Descriptions.stored(input.edited());
            }
            fields.put("TRTSEL" + slot.row(), mark == null ? "" : mark);
            fields.put("TRTYPD" + slot.row(), description);
            boolean editing = state.updatePending() && slot.row() == state.selectedRow();
            if (!editing) {
                readOnly.add("TRTYPD" + slot.row());
            }
            if (protectRows) {
                readOnly.add("TRTSEL" + slot.row());
            }
        }
        if (state.deletePending() || state.updatePending()) {
            readOnly.add("TRTYPE");
            readOnly.add("TRDESC");
        }
        return new Outcome.Show(fields, message, cursor, readOnly);
    }

    private static String like(String filter) {
        return filter == null || filter.isBlank() ? "" : "%" + filter.trim() + "%";
    }

    private record Input(String typeText, String descText, String typeFilter, String descFilter, boolean filterChanged,
                         boolean filterActive, String typeError, int deletes, int updates, int selected, String mark,
                         String edited, boolean unchanged, String markError, String editError, Slot slot) {

        static Input read(Map<String, String> fields, TranTypeListState state) {
            String typeText = fields.getOrDefault("TRTYPE", "");
            String descText = fields.getOrDefault("TRDESC", "");
            String typeError = null;
            String typeFilter = "";
            if (!typeText.isBlank() && !typeText.chars().allMatch(c -> c == '0')) {
                if (!typeText.chars().allMatch(Character::isDigit)) {
                    typeError = "TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER";
                } else {
                    typeFilter = typeText;
                }
            }
            boolean typeChanged = !sameType(typeText, state.typeFilter());
            boolean descChanged = !sameDesc(descText, state.descFilter());
            boolean filterChanged = typeChanged || descChanged;
            String storedType = filterChanged ? typeFilter : state.typeFilter();
            String storedDesc = filterChanged ? descText.trim() : state.descFilter();
            int deletes = 0;
            int updates = 0;
            int actions = 0;
            int selected = 0;
            String mark = "";
            String edited = "";
            String markError = null;
            Slot slot = null;
            if (!filterChanged && typeError == null) {
                for (int row = ROWS; row >= 1; row--) {
                    String chosen = fields.getOrDefault("TRTSEL" + row, "");
                    if (chosen.isBlank()) {
                        continue;
                    }
                    actions++;
                    int screenRow = row;
                    Slot found = state.slots().stream().filter(s -> s.row() == screenRow).findFirst()
                            .orElse(new Slot(row, fields.getOrDefault("TRTTYP" + row, ""),
                                    fields.getOrDefault("TRTYPD" + row, "")));
                    if ("D".equals(chosen) || "U".equals(chosen)) {
                        if ("D".equals(chosen)) {
                            deletes++;
                        } else {
                            updates++;
                        }
                        selected = row;
                        mark = chosen;
                        edited = fields.getOrDefault("TRTYPD" + row, "");
                        slot = found;
                    } else {
                        markError = "Action code selected is invalid";
                        selected = row;
                        mark = chosen;
                        slot = found;
                    }
                }
                if (actions > 1) {
                    markError = "Please select only 1 action";
                    deletes = 0;
                    updates = 0;
                }
            }
            boolean unchanged = "U".equals(mark) && slot != null && Descriptions.unchanged(edited, slot.description());
            String editError = "U".equals(mark) && actions == 1 && !unchanged ? Descriptions.problem(edited) : null;
            return new Input(blankType(typeText) ? "" : typeText, descText, storedType, storedDesc, filterChanged,
                    typeError == null && (!storedType.isBlank() || !storedDesc.isBlank()), typeError, deletes, updates,
                    selected, mark, edited, unchanged, markError, editError, slot);
        }

        static Input echo(TranTypeListState state) {
            return new Input(state.typeFilter(), state.descFilter(), state.typeFilter(), state.descFilter(), false,
                    !state.typeFilter().isBlank() || !state.descFilter().isBlank(), null, 0, 0, 0, "", "", false, null,
                    null, null);
        }

        boolean confirmable(TranTypeListState state) {
            return (state.deletePending() || state.updatePending()) && !filterChanged && selected == state.selectedRow();
        }

        String cursor() {
            return selected == 0 ? "TRTYPE" : "TRTSEL" + selected;
        }

        private static boolean blankType(String typed) {
            return typed.isBlank() || typed.chars().allMatch(c -> c == '0');
        }

        private static boolean sameType(String typed, String stored) {
            return blankType(typed) ? stored == null || stored.isBlank() : typed.equals(stored);
        }

        private static boolean sameDesc(String typed, String stored) {
            return typed.isBlank() ? stored == null || stored.isBlank() : typed.equals(stored);
        }
    }
}
