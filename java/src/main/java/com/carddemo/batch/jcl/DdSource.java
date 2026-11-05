package com.carddemo.batch.jcl;

import java.util.List;
import java.util.Optional;

/** What a DD names: a data set, instream cards, a SYSOUT class, or DUMMY. */
public sealed interface DdSource {

    record Dataset(DsnRef dsn, Disposition disposition, Optional<Dcb> dcb) implements DdSource {}

    /** {@code DD *}: the cards as written, each read as an 80-byte record. */
    record Instream(List<String> cards) implements DdSource {

        public Instream {
            cards = List.copyOf(cards);
        }
    }

    record Sysout(Optional<Dcb> dcb) implements DdSource {}

    record Dummy() implements DdSource {}
}
