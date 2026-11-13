package com.carddemo.online;

import com.carddemo.authorizations.AuthorizationKey;
import com.carddemo.authorizations.AuthorizationListPage;
import com.carddemo.authorizations.AuthorizationSelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.carddemo.online.SpecCase.text;

/**
 * CDEMO-ACCT-ID, CDEMO-CUST-ID and CDEMO-CPVS-INFO as AuthorizationListPage, and CDEMO-CPVS-PAU-SELECTED or its
 * COPAUS1C copy CDEMO-CPVD-PAU-SELECTED as AuthorizationSelection. Keys are written PA-AUTH-DATE-9C/PA-AUTH-TIME-9C.
 */
class AuthorizationCommarea implements CommareaMapping {

    @Override
    public void load(String program, Map<String, Object> commarea, Conversation conversation) {
        String account = text(commarea.get("CDEMO-ACCT-ID"));
        if (!account.matches("\\d{11}")) {
            return;
        }
        long accountId = Long.parseLong(account);
        String customer = text(commarea.get("CDEMO-CUST-ID"));
        int customerId = customer.matches("\\d+") ? Integer.parseInt(customer) : 0;
        conversation.put(new AuthorizationListPage(accountId, customerId, keys(commarea, "CDEMO-CPVS-PAUKEY-PREV-PG(%d)"),
                keys(commarea, "CDEMO-CPVS-AUTH-KEYS(%d)"), "Y".equals(text(commarea.get("CDEMO-CPVS-NEXT-PAGE-FLG")))));
        for (String name : List.of("CDEMO-CPVS-PAU-SELECTED", "CDEMO-CPVD-PAU-SELECTED")) {
            key(text(commarea.get(name)))
                    .ifPresent(k -> conversation.put(new AuthorizationSelection(accountId, customerId, k)));
        }
    }

    @Override
    public Optional<String> read(String name, Conversation conversation) {
        Optional<AuthorizationListPage> page = conversation.get(AuthorizationListPage.class);
        Optional<AuthorizationSelection> selection = conversation.get(AuthorizationSelection.class);
        if (name.matches("CDEMO-CPVS-AUTH-KEYS\\(\\d\\)")) {
            return Optional.of(nth(page.map(AuthorizationListPage::keys), name));
        }
        if (name.matches("CDEMO-CPVS-PAUKEY-PREV-PG\\(\\d+\\)")) {
            return Optional.of(nth(page.map(AuthorizationListPage::pageStarts), name));
        }
        return switch (name) {
            case "CDEMO-ACCT-ID" -> Optional.of(either(selection, page, AuthorizationSelection::accountId,
                    AuthorizationListPage::accountId).map("%011d"::formatted).orElse(""));
            case "CDEMO-CUST-ID" -> Optional.of(either(selection, page, AuthorizationSelection::customerId,
                    AuthorizationListPage::customerId).map("%09d"::formatted).orElse(""));
            case "CDEMO-CPVS-PAGE-NUM" -> Optional.of(String.valueOf(page.map(AuthorizationListPage::pageNumber).orElse(0)));
            case "CDEMO-CPVS-NEXT-PAGE-FLG" -> Optional.of(page.map(p -> p.hasNext() ? "Y" : "N").orElse(""));
            case "CDEMO-CPVS-PAUKEY-LAST" -> Optional.of(page.flatMap(AuthorizationListPage::last)
                    .map(AuthorizationCommarea::keyText).orElse(""));
            case "CDEMO-CPVS-PAU-SELECTED", "CDEMO-CPVD-PAU-SELECTED" -> Optional.of(selection
                    .map(s -> keyText(s.key())).orElse(""));
            default -> throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
        };
    }

    private static <T> Optional<T> either(Optional<AuthorizationSelection> selection, Optional<AuthorizationListPage> page,
                                          Function<AuthorizationSelection, T> fromSelection,
                                          Function<AuthorizationListPage, T> fromPage) {
        return selection.map(fromSelection).or(() -> page.map(fromPage));
    }

    private static String nth(Optional<List<AuthorizationKey>> keys, String name) {
        int n = Integer.parseInt(name.replaceAll(".*\\((\\d+)\\)", "$1"));
        return keys.filter(k -> k.size() >= n).map(k -> keyText(k.get(n - 1))).orElse("");
    }

    private static List<AuthorizationKey> keys(Map<String, Object> commarea, String name) {
        List<AuthorizationKey> keys = new ArrayList<>();
        for (int n = 1; ; n++) {
            Optional<AuthorizationKey> key = key(text(commarea.get(name.formatted(n))));
            if (key.isEmpty()) {
                return keys;
            }
            keys.add(key.get());
        }
    }

    private static Optional<AuthorizationKey> key(String text) {
        if (!text.matches("\\d+/\\d+")) {
            return Optional.empty();
        }
        String[] halves = text.split("/");
        return Optional.of(new AuthorizationKey(Integer.parseInt(halves[0]), Integer.parseInt(halves[1])));
    }

    private static String keyText(AuthorizationKey key) {
        return key.date9c() + "/" + key.time9c();
    }
}
