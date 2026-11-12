package com.carddemo.vsam;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DdlGeneratorTest {

    @Test
    void committedMigrationMatchesTheCopybooks() throws IOException {
        String committed = Files.readString(Path.of("src/main/resources/db/migration/V1__vsam_tables.sql"));

        assertThat(committed)
                .as("V1__vsam_tables.sql is stale; run ./gradlew generateDdl")
                .isEqualTo(DdlGenerator.generate());
    }
}
