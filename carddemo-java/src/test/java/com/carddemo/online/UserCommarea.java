package com.carddemo.online;

import com.carddemo.users.UserListPage;
import com.carddemo.users.UserSelection;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.carddemo.online.SpecCase.text;

/** CDEMO-CU00-INFO and its COUSR01C-03C copies, as UserListPage and UserSelection. */
class UserCommarea implements CommareaMapping {

    @Override
    public void load(String program, Map<String, Object> commarea, Conversation conversation) {
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
    }

    @Override
    public Optional<String> read(String name, Conversation conversation) {
        Optional<UserListPage> page = conversation.get(UserListPage.class);
        Optional<UserSelection> selection = conversation.get(UserSelection.class);
        return switch (name.replaceFirst("^CDEMO-CU0\\d-", "CDEMO-CUnn-")) {
            case "CDEMO-CUnn-USRID-FIRST" -> Optional.of(page.map(UserListPage::firstId).orElse(""));
            case "CDEMO-CUnn-USRID-LAST" -> Optional.of(page.map(UserListPage::lastId).orElse(""));
            case "CDEMO-CUnn-PAGE-NUM" -> Optional.of(page.map(p -> String.valueOf(p.pageNumber())).orElse(""));
            case "CDEMO-CUnn-NEXT-PAGE-FLG" -> Optional.of(page.map(p -> p.hasNext() ? "Y" : "N").orElse(""));
            case "CDEMO-CUnn-USR-SEL-FLG" -> Optional.of(selection.map(s -> s.action().name().substring(0, 1)).orElse(""));
            case "CDEMO-CUnn-USR-SELECTED" -> Optional.of(selection.map(UserSelection::userId).orElse(""));
            default -> throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
        };
    }
}
