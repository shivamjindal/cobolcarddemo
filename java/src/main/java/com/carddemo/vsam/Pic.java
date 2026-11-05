package com.carddemo.vsam;

public sealed interface Pic permits Pic.Alnum, Pic.Zoned, Pic.Packed, Pic.Binary {

    record Alnum(int length) implements Pic {}

    record Zoned(int digits, int scale, boolean signed) implements Pic {}

    record Packed(int digits, int scale, boolean signed) implements Pic {}

    record Binary(int digits, int scale, boolean signed) implements Pic {}
}
