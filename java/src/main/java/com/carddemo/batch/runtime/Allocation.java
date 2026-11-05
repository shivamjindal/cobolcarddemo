package com.carddemo.batch.runtime;

import java.util.List;
import java.util.Optional;

import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.records.KeyedTable;

/** What one data set of a DD statement was allocated to for the step. */
sealed interface Allocation {

    /** A KSDS held in a table, named by its cluster or its alternate-index path. */
    record Table(KeyedTable table) implements Allocation {}

    /** A data set store row; {@code gdg} when it is the job's {@code (+1)} generation of that base. */
    record Stored(DatasetStore.Dataset dataset, Disposition disposition, Optional<Gdg> gdg) implements Allocation {}

    record Instream(List<String> cards) implements Allocation {}

    record Sysout(Optional<Dcb> dcb) implements Allocation {}

    record Dummy() implements Allocation {}
}
