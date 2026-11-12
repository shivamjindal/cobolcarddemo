package com.carddemo.copybook;

public record Field(String name, int offset, Pic pic) {

    public int length() {
        return pic.storageLength();
    }

    public int end() {
        return offset + length();
    }

    public boolean isFiller() {
        return name.equals("FILLER") || name.endsWith("-FILLER");
    }
}
