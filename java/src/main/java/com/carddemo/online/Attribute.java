package com.carddemo.online;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** The keywords of a DFHMDF ATTRB= operand. */
public enum Attribute {
    ASKIP, PROT, UNPROT, NUM, BRT, NORM, DRK, IC, FSET;

    public static Set<Attribute> parse(String commaSeparated) {
        EnumSet<Attribute> set = EnumSet.noneOf(Attribute.class);
        if (!commaSeparated.isEmpty()) {
            Arrays.stream(commaSeparated.split(",")).map(Attribute::valueOf).forEach(set::add);
        }
        return Set.copyOf(set);
    }
}
