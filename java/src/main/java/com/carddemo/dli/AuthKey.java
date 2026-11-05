package com.carddemo.dli;

import java.io.Serializable;
import java.util.Comparator;

/**
 * PA-AUTHORIZATION-KEY, the PAUTDTL1 key field PAUT9CTS: 99999 - YYDDD and 999999999 - HHMMSSmmm of the
 * authorization, so a later authorization has the smaller key and comes first in twin order.
 */
public record AuthKey(int authDate9c, int authTime9c) implements Comparable<AuthKey>, Serializable {

    private static final Comparator<AuthKey> ORDER = Comparator.comparingInt(AuthKey::authDate9c)
            .thenComparingInt(AuthKey::authTime9c);

    public AuthKey {
        Image.checkInteger(authDate9c, PendingAuthDetail.AUTH_DATE_9C);
        Image.checkInteger(authTime9c, PendingAuthDetail.AUTH_TIME_9C);
        if (authDate9c < 0 || authTime9c < 0) {
            throw new IllegalArgumentException("PAUT9CTS parts must not be negative: " + authDate9c + "/" + authTime9c);
        }
    }

    @Override
    public int compareTo(AuthKey other) {
        return ORDER.compare(this, other);
    }
}
