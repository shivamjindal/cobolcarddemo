package com.carddemo.vsam;

import java.util.List;

public record IndexDef(String name, List<Column> columns, boolean unique) {

    public IndexDef {
        columns = List.copyOf(columns);
    }
}
