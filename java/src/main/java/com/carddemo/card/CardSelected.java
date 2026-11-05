package com.carddemo.card;

import com.carddemo.online.Handoff;

/**
 * CDEMO-ACCT-ID and CDEMO-CARD-NUM, which the card list sends to detail and update. Both screens
 * declare this handoff, so a sender uses this one class. A menu arrival has none of it.
 */
public record CardSelected(long acctId, String cardNum) implements Handoff {}
