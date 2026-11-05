package com.carddemo.vsam;

public record Field(String cobolName, int offset, int length, Pic pic, String picClause) {}
