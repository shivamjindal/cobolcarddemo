package com.carddemo.vsam;

public record Cluster(String dsn, KeyRange key, int recordLength) {}
