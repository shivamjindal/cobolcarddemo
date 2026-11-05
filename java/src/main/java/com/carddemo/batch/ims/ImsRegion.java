package com.carddemo.batch.ims;

import com.carddemo.dli.Pcb;

/** What a {@link DliProgram} sees of its region: the PSB's database PCB and the CHKP call. */
public interface ImsRegion {

    /** The PSB's PCB on DBPAUTP0. */
    Pcb database();

    /** {@code CHKP}: commits the database changes made since the last checkpoint. */
    void checkpoint();
}
