package com.carddemo.authorizations;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * CDEMO-CPVS-INFO: the account COPAUS0C lists, the first key of every page shown on the way to the current one
 * (CDEMO-CPVS-PAUKEY-PREV-PG), and the current page's keys. The page number is the number of pages so far.
 */
public record AuthorizationListPage(long accountId, int customerId, List<AuthorizationKey> pageStarts,
                                    List<AuthorizationKey> keys, boolean hasNext) implements Serializable {

    public AuthorizationListPage {
        pageStarts = List.copyOf(pageStarts);
        keys = List.copyOf(keys);
    }

    /** An account chosen but not listed yet. */
    public static AuthorizationListPage of(long accountId, int customerId) {
        return new AuthorizationListPage(accountId, customerId, List.of(), List.of(), false);
    }

    public int pageNumber() {
        return pageStarts.size();
    }

    public Optional<AuthorizationKey> last() {
        return keys.isEmpty() ? Optional.empty() : Optional.of(keys.getLast());
    }
}
