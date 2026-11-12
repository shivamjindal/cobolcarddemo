package com.carddemo.cards;

import java.io.Serializable;

/**
 * WS-THIS-PROGCOMMAREA of COCRDUPC. {@code fetched} is the card as read (CCUP-OLD-*), the snapshot a save
 * compares the locked row against; {@code validated} is what CONFIRM shows and F5 writes (CCUP-NEW-*).
 */
public record CardUpdateState(CardUpdatePhase phase, Card fetched, Card validated) implements Serializable {

    public static final CardUpdateState NOT_FETCHED = new CardUpdateState(CardUpdatePhase.NOT_FETCHED, null, null);

    public CardUpdateState {
        boolean valid = switch (phase) {
            case NOT_FETCHED -> fetched == null && validated == null;
            case SHOWN, INVALID -> fetched != null;
            case CONFIRM -> fetched != null && validated != null;
            case SAVED, LOCK_FAILED, SAVE_FAILED -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException(phase + " with fetched " + fetched + " and validated " + validated);
        }
    }

    static CardUpdateState shown(Card fetched) {
        return new CardUpdateState(CardUpdatePhase.SHOWN, fetched, null);
    }
}
