package com.carddemo.transactions;

import java.io.Serializable;

/** CDEMO-CT00-INFO: the page COTRN00C shows, kept between key presses. */
public record TransactionListPage(String firstId, String lastId, int pageNumber, boolean hasNext) implements Serializable {

    static final TransactionListPage FIRST = new TransactionListPage("", "", 1, false);
}
