package com.carddemo.vsam;

public record Dataset(String table, String clusterDsn, String copybook, String dataFile) {}
