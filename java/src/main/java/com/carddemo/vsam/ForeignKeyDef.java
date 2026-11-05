package com.carddemo.vsam;

import java.util.List;

public record ForeignKeyDef(
        String name, List<Column> columns, String parentTable, List<String> parentColumns) {

    public ForeignKeyDef {
        columns = List.copyOf(columns);
        parentColumns = List.copyOf(parentColumns);
    }
}
