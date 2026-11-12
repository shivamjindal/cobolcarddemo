package com.carddemo.vsam;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SeedFormatTest {

    private static final Path DATA = Path.of(System.getProperty("carddemo.legacy-app"), "data");

    // The two sample sets in app/data were exported separately and disagree on these records (0-based).
    // EBCDIC is what the REPRO jobs load: account 49's ZIP is ZEROAPR, and DEFAULT/07/0001 has a 15.00 rate.
    private static final Map<VsamDataset, List<Integer>> KNOWN_DIVERGENCES = Map.of(
            VsamDataset.ACCOUNTS, List.of(48),
            VsamDataset.DISCLOSURE_GROUPS, List.of(33));

    @ParameterizedTest
    @EnumSource(VsamDataset.class)
    void asciiAndEbcdicSamplesDecodeToTheSameRows(VsamDataset dataset) {
        assumeTrue(dataset.asciiFile != null, "no ASCII sample for " + dataset);
        Table table = Table.of(dataset);

        List<List<Object>> ebcdic = rows(table, SeedFormat.EBCDIC.records(DATA.resolve("EBCDIC"), dataset).orElseThrow());
        List<List<Object>> ascii = rows(table, SeedFormat.ASCII.records(DATA.resolve("ASCII"), dataset).orElseThrow());

        assertThat(ascii).isNotEmpty().hasSameSizeAs(ebcdic);
        assertThat(IntStream.range(0, ascii.size()).filter(i -> !ascii.get(i).equals(ebcdic.get(i))).boxed().toList())
                .isEqualTo(KNOWN_DIVERGENCES.getOrDefault(dataset, List.of()));
    }

    private static List<List<Object>> rows(Table table, List<String> records) {
        return records.stream().map(r -> Arrays.asList(table.decode(r))).toList();
    }
}
