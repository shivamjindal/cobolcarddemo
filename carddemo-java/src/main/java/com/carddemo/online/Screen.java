package com.carddemo.online;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/** A rendered screen: what the HTML templates draw and what the REST adapter returns. */
public record Screen(Program program, String transid, Map<String, String> fields, Message message, String cursor,
                     Set<String> readOnly) implements Serializable {
}
