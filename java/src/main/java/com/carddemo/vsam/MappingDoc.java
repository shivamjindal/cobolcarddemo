package com.carddemo.vsam;

import java.util.List;
import java.util.stream.Collectors;

final class MappingDoc {

    private MappingDoc() {}

    static String render(List<TableDef> tables, List<Cluster> clusters, List<List<String>> reasons) {
        StringBuilder doc = new StringBuilder();
        doc.append("# VSAM KSDS schema mapping\n\n");
        doc.append("SchemaGenerator writes this file from the copybooks, LISTCAT, and EBCDIC sample rows. ");
        doc.append("The Flyway migration is the same run.\n");
        for (int i = 0; i < tables.size(); i++) {
            appendTable(doc, tables.get(i), clusters.get(i), reasons.get(i));
        }
        return doc.toString();
    }

    private static void appendTable(StringBuilder doc, TableDef table, Cluster cluster, List<String> reasons) {
        Dataset dataset = table.dataset();
        doc.append("\n## ").append(dataset.table()).append("\n\n");
        doc.append("Cluster `").append(cluster.dsn())
                .append("`, copybook `").append(dataset.copybook())
                .append("`, record `").append(table.layout().recordName())
                .append("`, length ").append(table.layout().recordLength())
                .append(", key offset ").append(cluster.key().offset())
                .append(" length ").append(cluster.key().length())
                .append(". Data file `").append(dataset.dataFile()).append("`.\n\n");
        doc.append("| COBOL name | PIC | Offset | Length | SQL type | Reason |\n");
        doc.append("| --- | --- | --- | --- | --- | --- |\n");
        List<Column> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            Field field = column.field();
            doc.append("| ").append(field.cobolName())
                    .append(" | ").append(field.picClause())
                    .append(" | ").append(field.offset())
                    .append(" | ").append(field.length())
                    .append(" | ").append(Ddl.typeName(column.type()))
                    .append(" | ").append(reasons.get(i))
                    .append(" |\n");
        }
        doc.append("\nPrimary key is `")
                .append(table.primaryKey().stream().map(Column::name).collect(Collectors.joining("`, `")))
                .append("`.\n\n");
        doc.append("Alternate indexes\n\n");
        if (table.indexes().isEmpty()) {
            doc.append("None.\n\n");
        } else {
            for (IndexDef index : table.indexes()) {
                String columnsSql = index.columns().stream().map(Column::name).collect(Collectors.joining(", "));
                doc.append("- `").append(index.name()).append("` (").append(columnsSql).append(")");
                if (index.unique()) {
                    doc.append(", unique");
                }
                doc.append('\n');
            }
            doc.append('\n');
        }
        doc.append("Foreign keys\n\n");
        if (table.foreignKeys().isEmpty()) {
            doc.append("None.\n\n");
        } else {
            for (ForeignKeyDef foreignKey : table.foreignKeys()) {
                doc.append("- `").append(foreignKey.name()).append("` (")
                        .append(foreignKey.columns().stream().map(Column::name).collect(Collectors.joining(", ")))
                        .append(") references `").append(foreignKey.parentTable()).append("` (")
                        .append(String.join(", ", foreignKey.parentColumns())).append(")\n");
            }
            doc.append('\n');
        }
        doc.append("Notes\n\n");
        if (table.notes().isEmpty()) {
            doc.append("None.\n");
        } else {
            for (String note : table.notes()) {
                doc.append("- ").append(note).append('\n');
            }
        }
    }
}
