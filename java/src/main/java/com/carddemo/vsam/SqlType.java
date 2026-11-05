package com.carddemo.vsam;

public sealed interface SqlType
        permits SqlType.Text, SqlType.Int, SqlType.BigInt, SqlType.Numeric, SqlType.Date, SqlType.Timestamp {

    record Text(int length) implements SqlType {}

    record Int(int digits) implements SqlType {}

    record BigInt(int digits) implements SqlType {}

    record Numeric(int precision, int scale, boolean signed) implements SqlType {}

    record Date() implements SqlType {}

    record Timestamp() implements SqlType {}
}
