package com.carddemo.vsam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Keys {

    private Keys() {}

    public static List<Field> cover(Layout layout, KeyRange key) {
        int start = key.offset();
        int end = start + key.length();
        if (start < 0 || key.length() <= 0) {
            throw new IllegalArgumentException("key offset " + start + " length " + key.length());
        }
        List<Field> ordered = new ArrayList<>();
        ordered.addAll(layout.fields());
        ordered.addAll(layout.fillers());
        ordered.sort(Comparator.comparingInt(Field::offset).thenComparing(Field::cobolName));
        List<Field> covered = new ArrayList<>();
        int cursor = start;
        for (Field field : ordered) {
            int fieldEnd = field.offset() + field.length();
            if (fieldEnd <= start || field.offset() >= end) {
                continue;
            }
            if (field.offset() < start || fieldEnd > end) {
                throw new IllegalArgumentException(field.cobolName() + " straddles key offset " + start
                        + " length " + key.length());
            }
            if (field.cobolName().equalsIgnoreCase("FILLER")) {
                throw new IllegalArgumentException("key covers FILLER at offset " + field.offset());
            }
            if (field.offset() != cursor) {
                throw new IllegalArgumentException("gap in key at offset " + cursor);
            }
            covered.add(field);
            cursor = fieldEnd;
        }
        if (cursor != end || covered.isEmpty()) {
            throw new IllegalArgumentException("key offset " + start + " length " + key.length()
                    + " is not an exact field span");
        }
        return List.copyOf(covered);
    }
}
