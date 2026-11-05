package com.carddemo.vsam;

import java.util.List;

public record ForeignKeyCandidate(String childTable, List<String> childColumns, String parentTable) {

    public ForeignKeyCandidate {
        childColumns = List.copyOf(childColumns);
    }
}
