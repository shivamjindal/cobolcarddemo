package com.carddemo.online;

import java.util.Locale;

/** Implemented by each screen's action enum. */
public interface ScreenAction {

    /** Reserved for the {@code WHEN OTHER} branch of {@code EVALUATE EIBAID}. */
    String INVALID_KEY = "invalid-key";

    Key key();

    String name();

    /** The URL segment: the constant name in kebab case. */
    default String route() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
