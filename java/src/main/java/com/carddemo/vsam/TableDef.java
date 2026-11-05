package com.carddemo.vsam;

import java.util.List;

public record TableDef(
        Dataset dataset,
        Layout layout,
        List<Column> columns,
        List<Column> primaryKey,
        List<IndexDef> indexes,
        List<ForeignKeyDef> foreignKeys,
        List<String> notes) {

    public TableDef {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        indexes = List.copyOf(indexes);
        foreignKeys = List.copyOf(foreignKeys);
        notes = List.copyOf(notes);
    }
}
