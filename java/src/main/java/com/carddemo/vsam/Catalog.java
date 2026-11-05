package com.carddemo.vsam;

import java.util.List;

public record Catalog(List<Cluster> clusters, List<AlternateIndex> indexes) {

    public Catalog {
        clusters = List.copyOf(clusters);
        indexes = List.copyOf(indexes);
    }
}
