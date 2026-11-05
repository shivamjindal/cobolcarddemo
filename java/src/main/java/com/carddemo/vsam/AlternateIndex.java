package com.carddemo.vsam;

public record AlternateIndex(String dsn, String clusterDsn, KeyRange key, boolean unique) {}
