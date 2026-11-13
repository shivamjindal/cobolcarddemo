package com.carddemo.trantypes;

import java.io.Serializable;

/** One TRANSACTION_TYPE row: the two-character code and its description. */
public record TranType(String code, String description) implements Serializable {
}
