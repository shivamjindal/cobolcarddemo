package com.carddemo.online;

import java.util.Set;

/**
 * One DFHMDF as BMS defines it.
 *
 * @param col     the POS column of the attribute byte; the data starts one column to the right
 * @param initial the INITIAL value, "" when absent
 * @param color   the COLOR operand, null when absent
 * @param hilight the HILIGHT operand, null when absent
 */
public record BmsField(int row, int col, int length, Set<Attribute> attributes, String initial, Color color,
        String hilight) {

    public static BmsField of(int row, int col, int length, String attributes, String initial, Color color,
            String hilight) {
        return new BmsField(row, col, length, Attribute.parse(attributes), initial, color, hilight);
    }

    public boolean has(Attribute attribute) {
        return attributes.contains(attribute);
    }

    public boolean protectedByDefault() {
        return has(Attribute.PROT) || has(Attribute.ASKIP);
    }
}
