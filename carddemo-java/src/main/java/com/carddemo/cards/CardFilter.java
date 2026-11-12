package com.carddemo.cards;

import java.io.Serializable;

/** The account and card filters of the card list; a null field filters nothing. */
public record CardFilter(Long accountId, String cardNumber) implements Serializable {

    public static final CardFilter NONE = new CardFilter(null, null);
}
