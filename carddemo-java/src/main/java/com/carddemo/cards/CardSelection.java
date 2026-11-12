package com.carddemo.cards;

import java.io.Serializable;

/** CDEMO-ACCT-ID and CDEMO-CARD-NUM of the row picked on the card list, handed to COCRDSLC or COCRDUPC. */
public record CardSelection(long accountId, String cardNumber) implements Serializable {
}
