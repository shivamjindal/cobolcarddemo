package com.carddemo.online;

/**
 * Every CardDemo online program, migrated or not, with its transid from app/csd/CARDDEMO.CSD (or the
 * program source for the optional modules). Menus reference the unmigrated ones; a program with no
 * {@link ScreenProgram} bean is not installed.
 */
public enum Program {
    COSGN00C("CC00", Access.PUBLIC),
    COMEN01C("CM00", Access.SIGNED_ON),
    COADM01C("CA00", Access.ADMIN),
    COUSR00C("CU00", Access.ADMIN),
    COUSR01C("CU01", Access.ADMIN),
    COUSR02C("CU02", Access.ADMIN),
    COUSR03C("CU03", Access.ADMIN),
    COACTVWC("CAVW", Access.SIGNED_ON),
    COACTUPC("CAUP", Access.SIGNED_ON),
    COCRDLIC("CCLI", Access.SIGNED_ON),
    COCRDSLC("CCDL", Access.SIGNED_ON),
    COCRDUPC("CCUP", Access.SIGNED_ON),
    COTRN00C("CT00", Access.SIGNED_ON),
    COTRN01C("CT01", Access.SIGNED_ON),
    COTRN02C("CT02", Access.SIGNED_ON),
    CORPT00C("CR00", Access.SIGNED_ON),
    COBIL00C("CB00", Access.SIGNED_ON),
    COPAUS0C("CPVS", Access.SIGNED_ON),
    COPAUS1C("CPVD", Access.SIGNED_ON),
    COTRTLIC("CTLI", Access.ADMIN),
    COTRTUPC("CTTU", Access.ADMIN);

    public enum Access { PUBLIC, SIGNED_ON, ADMIN }

    public final String transid;
    public final Access access;

    Program(String transid, Access access) {
        this.transid = transid;
        this.access = access;
    }
}
