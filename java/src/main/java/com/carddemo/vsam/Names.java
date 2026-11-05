package com.carddemo.vsam;

import java.util.Locale;

public final class Names {

    private Names() {}

    public static String column(String cobolName) {
        return cobolName.toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
