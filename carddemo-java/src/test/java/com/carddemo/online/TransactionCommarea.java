package com.carddemo.online;

import com.carddemo.transactions.TransactionListPage;
import com.carddemo.transactions.TransactionSelection;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.carddemo.online.SpecCase.text;

/** CDEMO-CT00-INFO and its COTRN01C copy CDEMO-CT01-INFO, as TransactionListPage and TransactionSelection. */
class TransactionCommarea implements CommareaMapping {

    @Override
    public void load(String program, Map<String, Object> commarea, Conversation conversation) {
        Map<String, String> info = new HashMap<>();
        commarea.forEach((key, value) -> {
            if (key.matches("CDEMO-CT0\\d-.+")) {
                info.put(key.substring("CDEMO-CT00-".length()), text(value));
            }
        });
        if (info.keySet().stream().anyMatch(k -> k.startsWith("TRNID-") || k.equals("PAGE-NUM") || k.equals("NEXT-PAGE-FLG"))) {
            conversation.put(new TransactionListPage(info.getOrDefault("TRNID-FIRST", ""), info.getOrDefault("TRNID-LAST", ""),
                    Integer.parseInt(info.getOrDefault("PAGE-NUM", "1")), "Y".equals(info.get("NEXT-PAGE-FLG"))));
        }
        String selected = info.getOrDefault("TRN-SELECTED", "");
        if (!selected.isBlank()) {
            conversation.put(new TransactionSelection(selected));
        }
    }

    @Override
    public Optional<String> read(String name, Conversation conversation) {
        Optional<TransactionListPage> page = conversation.get(TransactionListPage.class);
        Optional<TransactionSelection> selection = conversation.get(TransactionSelection.class);
        return switch (name.replaceFirst("^CDEMO-CT0\\d-", "CDEMO-CTnn-")) {
            case "CDEMO-CTnn-TRNID-FIRST" -> Optional.of(page.map(TransactionListPage::firstId).orElse(""));
            case "CDEMO-CTnn-TRNID-LAST" -> Optional.of(page.map(TransactionListPage::lastId).orElse(""));
            case "CDEMO-CTnn-PAGE-NUM" -> Optional.of(page.map(p -> String.valueOf(p.pageNumber())).orElse(""));
            case "CDEMO-CTnn-NEXT-PAGE-FLG" -> Optional.of(page.map(p -> p.hasNext() ? "Y" : "N").orElse(""));
            case "CDEMO-CTnn-TRN-SEL-FLG" -> Optional.of(selection.map(s -> "S").orElse(""));
            case "CDEMO-CTnn-TRN-SELECTED" -> Optional.of(selection.map(TransactionSelection::transactionId).orElse(""));
            default -> throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
        };
    }
}
