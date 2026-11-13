package com.carddemo.trantypes;

import java.io.Serializable;
import java.util.List;

/**
 * The list screen's COMMAREA: the filters, the page anchor, and whether a delete or update is waiting for F10.
 * {@code anchor} is the key PF8 starts from. A full page with a further row stores that further row's code,
 * because the forward cursor's extra fetch overwrites the anchor.
 */
public record TranTypeListState(String typeFilter, String descFilter, int page, String firstCode, String anchor,
                         boolean nextPage, boolean lastPageShown, List<Slot> slots, int selectedRow,
                         boolean deletePending, boolean updatePending) implements Serializable {

    public record Slot(int row, String code, String description) implements Serializable {
    }

    static TranTypeListState fresh() {
        return new TranTypeListState("", "", 1, "", "", false, false, List.of(), 0, false, false);
    }

    TranTypeListState withPage(int page, String firstCode, String anchor, boolean nextPage, List<Slot> slots) {
        return new TranTypeListState(typeFilter, descFilter, page, firstCode, anchor, nextPage, false, slots,
                0, false, false);
    }

    TranTypeListState filtered(String typeFilter, String descFilter) {
        return new TranTypeListState(typeFilter, descFilter, 1, "", "", false, false, List.of(), 0, false, false);
    }

    TranTypeListState pending(boolean deletePending, boolean updatePending, int selectedRow) {
        return new TranTypeListState(typeFilter, descFilter, page, firstCode, anchor, nextPage, lastPageShown, slots,
                selectedRow, deletePending, updatePending);
    }

    TranTypeListState lastPageNoted() {
        return new TranTypeListState(typeFilter, descFilter, page, firstCode, anchor, nextPage, true, slots,
                selectedRow, deletePending, updatePending);
    }
}
