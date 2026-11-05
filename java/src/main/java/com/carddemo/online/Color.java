package com.carddemo.online;

/** DFHBMSCA color constants, named as COBOL names them without the DFH prefix. */
public enum Color {
    DFCOL, BLUE, RED, PINK, GREEN, TURQ, YELLO, NEUTR;

    /** The BMS COLOR= operand. */
    public static Color ofBms(String operand) {
        return switch (operand) {
            case "DEFAULT" -> DFCOL;
            case "BLUE" -> BLUE;
            case "RED" -> RED;
            case "PINK" -> PINK;
            case "GREEN" -> GREEN;
            case "TURQUOISE" -> TURQ;
            case "YELLOW" -> YELLO;
            case "NEUTRAL" -> NEUTR;
            default -> throw new IllegalArgumentException("BMS color " + operand);
        };
    }
}
