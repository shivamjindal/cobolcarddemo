package com.carddemo.online;

/** DFHAID keys, named as the characterization harness names them. */
public enum Key {
    ENTER, CLEAR, PA1, PA2, PA3,
    PF1, PF2, PF3, PF4, PF5, PF6, PF7, PF8, PF9, PF10, PF11, PF12,
    PF13, PF14, PF15, PF16, PF17, PF18, PF19, PF20, PF21, PF22, PF23, PF24;

    /** CLEAR and the PA keys transmit no field data, so RECEIVE MAP gets MAPFAIL. */
    public boolean sendsData() {
        return this != CLEAR && this != PA1 && this != PA2 && this != PA3;
    }
}
