package com.carddemo.batch.utilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The DFSORT control statements the library runs, one record per statement set. Each sort is stable: DFSORT
 * without EQUALS may order equal keys either way, and input order is one of the orders it may produce.
 */
public sealed interface SortSpec {

    /** SORTIN records, the concatenation in order, to SORTOUT records. */
    List<byte[]> apply(List<byte[]> sortin);

    /**
     * CREASTMT STEP010: {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}, {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)},
     * into the 350-byte SORTOUT. The output leads with card number and transaction id, the TRXFL key.
     */
    record CreastmtTrxfl() implements SortSpec {

        private static final int SORTOUT = 350;
        private static final int CARD = 262;
        private static final int KEY = 32;

        @Override
        public List<byte[]> apply(List<byte[]> sortin) {
            List<byte[]> sorted = new ArrayList<>(sortin.size());
            for (byte[] in : sortin) {
                byte[] out = new byte[SORTOUT];
                Arrays.fill(out, (byte) ' ');
                System.arraycopy(in, CARD, out, 0, 16);
                System.arraycopy(in, 0, out, 16, CARD);
                System.arraycopy(in, 278, out, 278, 50);
                sorted.add(out);
            }
            sorted.sort((a, b) -> Arrays.compareUnsigned(a, 0, KEY, b, 0, KEY));
            return sorted;
        }
    }
}
