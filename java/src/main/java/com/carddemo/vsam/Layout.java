package com.carddemo.vsam;

import java.util.List;

public record Layout(String recordName, int recordLength, List<Field> fields, List<Field> fillers) {

    public Layout {
        fields = List.copyOf(fields);
        fillers = List.copyOf(fillers);
    }
}
