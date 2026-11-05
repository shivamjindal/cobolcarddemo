package com.carddemo.batch.library;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.carddemo.batch.jcl.JclDeck;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.vsam.Fixtures;

class LibraryJobTest {

    @Test
    void eachMemberParsesToItsLibraryJob() throws Exception {
        for (LibraryJob job : List.of(LibraryJob.TRANTYPE, LibraryJob.TRANCATG, LibraryJob.TCATBALF,
                LibraryJob.DISCGRP, LibraryJob.PRTCATBL)) {
            List<String> cards = Files.readAllLines(Fixtures.appRoot().resolve("jcl").resolve(job.name() + ".jcl"),
                    ISO_8859_1);
            assertEquals(job.job(), parse(cards), job.name());
        }
    }

    private static JobDef parse(List<String> cards) {
        return switch (JclDeck.parse(cards, Proc::byMember)) {
            case JclDeck.Parsed.Job parsed -> parsed.job();
            case JclDeck.Parsed.Error error -> throw new AssertionError(error.reason());
        };
    }
}
