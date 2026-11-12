package com.carddemo.online;

import com.carddemo.trantypes.TranTypeForm;
import com.carddemo.trantypes.TranTypeListState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** COTRTLIC and COTRTUPC keep their COMMAREA page and edit phase on the conversation. */
public class TranTypeCommarea implements CommareaMapping {

    @Override
    public void load(String program, Map<String, Object> commarea, Conversation conversation) {
        if ("COTRTLIC".equals(program)) {
            loadList(commarea, conversation);
        } else if ("COTRTUPC".equals(program)) {
            loadForm(commarea, conversation);
        }
    }

    @Override
    public Optional<String> read(String name, Conversation conversation) {
        Optional<TranTypeListState> list = conversation.get(TranTypeListState.class);
        if (list.isPresent()) {
            TranTypeListState state = list.get();
            return Optional.of(switch (name) {
                case "WS-CA-TYPE-CD" -> state.typeFilter();
                case "WS-CA-TYPE-DESC" -> state.descFilter();
                case "WS-CA-SCREEN-NUM" -> Integer.toString(state.page());
                case "WS-CA-FIRST-TR-CODE" -> state.firstCode();
                case "WS-CA-LAST-TR-CODE" -> state.anchor();
                case "WS-CA-NEXT-PAGE-IND" -> state.nextPage() ? "Y" : "";
                case "WS-CA-DELETE-FLAG" -> state.deletePending() ? "Y" : "";
                case "WS-CA-UPDATE-FLAG" -> state.updatePending() ? "Y" : "";
                default -> throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
            });
        }
        TranTypeForm form = conversation.get(TranTypeForm.class).orElse(null);
        if (form == null) {
            throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
        }
        return Optional.of(switch (name) {
            case "TTUP-CHANGE-ACTION" -> form.phase().code();
            case "TTUP-OLD-TTYP-TYPE", "TTUP-NEW-TTYP-TYPE" -> form.code();
            case "TTUP-OLD-TTYP-TYPE-DESC" -> form.description();
            case "TTUP-NEW-TTYP-TYPE-DESC" -> form.edited();
            default -> throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
        });
    }

    private static void loadList(Map<String, Object> commarea, Conversation conversation) {
        if (!commarea.containsKey("WS-CA-SCREEN-NUM") && !commarea.containsKey("WS-CA-ROWS")
                && !commarea.containsKey("WS-CA-NEXT-PAGE-IND") && !commarea.containsKey("WS-CA-DELETE-FLAG")) {
            return;
        }
        List<TranTypeListState.Slot> slots = new ArrayList<>();
        Object rows = commarea.get("WS-CA-ROWS");
        if (rows instanceof List<?> list) {
            int row = 1;
            for (Object item : list) {
                Map<String, Object> values = map(item);
                String code = SpecCase.text(values.get("CODE"));
                if (!code.isBlank()) {
                    slots.add(new TranTypeListState.Slot(row, code, SpecCase.text(values.get("DESC"))));
                }
                row++;
            }
        }
        int page = number(commarea.get("WS-CA-SCREEN-NUM"), 1);
        conversation.put(new TranTypeListState(SpecCase.text(commarea.get("WS-CA-TYPE-CD")),
                SpecCase.text(commarea.get("WS-CA-TYPE-DESC")), page,
                SpecCase.text(commarea.get("WS-CA-FIRST-TR-CODE")),
                SpecCase.text(commarea.get("WS-CA-LAST-TR-CODE")),
                "Y".equals(SpecCase.text(commarea.get("WS-CA-NEXT-PAGE-IND"))),
                "0".equals(SpecCase.text(commarea.get("WS-CA-LAST-PAGE-DISPLAYED"))), slots,
                number(commarea.get("WS-CA-ROW-SELECTED"), 0),
                "Y".equals(SpecCase.text(commarea.get("WS-CA-DELETE-FLAG"))),
                "Y".equals(SpecCase.text(commarea.get("WS-CA-UPDATE-FLAG")))));
    }

    private static void loadForm(Map<String, Object> commarea, Conversation conversation) {
        if (!commarea.containsKey("TTUP-CHANGE-ACTION") && !commarea.containsKey("TTUP-OLD-TTYP-TYPE")) {
            return;
        }
        String code = first(commarea, "TTUP-OLD-TTYP-TYPE", "TTUP-NEW-TTYP-TYPE");
        String description = SpecCase.text(commarea.get("TTUP-OLD-TTYP-TYPE-DESC"));
        String edited = commarea.containsKey("TTUP-NEW-TTYP-TYPE-DESC")
                ? SpecCase.text(commarea.get("TTUP-NEW-TTYP-TYPE-DESC")) : description;
        conversation.put(new TranTypeForm(TranTypeForm.Phase.of(SpecCase.text(commarea.get("TTUP-CHANGE-ACTION"))),
                code, description, edited));
    }

    private static String first(Map<String, Object> commarea, String... names) {
        for (String name : names) {
            if (commarea.containsKey(name)) {
                return SpecCase.text(commarea.get(name));
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> values ? (Map<String, Object>) values : Map.of();
    }

    private static int number(Object value, int fallback) {
        String text = SpecCase.text(value);
        if (text.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(text.trim());
    }
}
