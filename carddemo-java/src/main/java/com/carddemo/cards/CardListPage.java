package com.carddemo.cards;

import java.io.Serializable;
import java.util.List;

/**
 * WS-THIS-PROGCOMMAREA of COCRDLIC: the page shown, the filter it was read with, and its rows, kept between
 * key presses and across a visit to card detail or update. {@code lastCard} is the first card of the next
 * page when {@code hasNext}; {@code lastPageShown} is WS-CA-LAST-PAGE-DISPLAYED.
 */
public record CardListPage(int pageNumber, String firstCard, String lastCard, boolean hasNext, boolean lastPageShown,
                           CardFilter filter, List<Row> rows) implements Serializable {

    public record Row(long accountId, String cardNumber, String activeStatus) implements Serializable {
    }

    public static final CardListPage FIRST = new CardListPage(1, "", "", false, false, CardFilter.NONE, List.of());

    public CardListPage {
        rows = List.copyOf(rows);
    }
}
