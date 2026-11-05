package com.carddemo.batch.chains;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.carddemo.batch.library.LibraryJob;

/**
 * The chains of {@code app/scheduler}, by name. A CA-7 chain is one walk of the LJOB export with its loop edges
 * back to CLOSEFIL dropped; a Control-M chain is a folder, each job waiting on the condition of the one before.
 */
@Component
public final class Chains {

    static final Chain CA7_030_POSTING = new Chain("CA7_030_POSTING", Chain.Scheduler.CA7, "030", List.of(
            job(LibraryJob.CLOSEFIL), new Member.Optional("CBPAUP0J", "IMS"), job(LibraryJob.POSTTRAN),
            job(LibraryJob.WAITSTEP), job(LibraryJob.OPENFIL)),
            "CA-7 SCHID 030; QTM=0100; the export has no days", Optional.empty());

    static final Chain CA7_030_EXTRACTS = new Chain("CA7_030_EXTRACTS", Chain.Scheduler.CA7, "030", List.of(
            job(LibraryJob.CLOSEFIL), job(LibraryJob.READACCT), job(LibraryJob.READCARD), job(LibraryJob.READCUST),
            job(LibraryJob.READXREF), job(LibraryJob.WAITSTEP), job(LibraryJob.OPENFIL)),
            "CA-7 SCHID 030; QTM=0100; the export has no days", Optional.empty());

    static final Chain CA7_030_STATEMENTS = new Chain("CA7_030_STATEMENTS", Chain.Scheduler.CA7, "030", List.of(
            job(LibraryJob.CLOSEFIL), job(LibraryJob.CREASTMT), new Member.Optional("TXT2PDF1", "REXX"),
            job(LibraryJob.WAITSTEP), job(LibraryJob.OPENFIL)),
            "CA-7 SCHID 030; QTM=0100; the export has no days", Optional.empty());

    /** SCHID 030, then the 031 and 032 tails its WAITSTEP triggers; no step of the walk opens the files again. */
    static final Chain CA7_030_REFERENCE = new Chain("CA7_030_REFERENCE", Chain.Scheduler.CA7, "030", List.of(
            job(LibraryJob.CLOSEFIL), job(LibraryJob.TRANTYPE), job(LibraryJob.WAITSTEP),
            job(LibraryJob.CLOSEFIL), job(LibraryJob.TRANCATG), job(LibraryJob.WAITSTEP),
            job(LibraryJob.CLOSEFIL), job(LibraryJob.TCATBALF), job(LibraryJob.WAITSTEP)),
            "CA-7 SCHID 030, then 031 and 032; QTM=0100; the export has no days", Optional.empty());

    static final Chain CA7_031_CATBAL = new Chain("CA7_031_CATBAL", Chain.Scheduler.CA7, "031", List.of(
            job(LibraryJob.CLOSEFIL), job(LibraryJob.PRTCATBL),
            job(LibraryJob.WAITSTEP), job(LibraryJob.OPENFIL)),
            "CA-7 SCHID 031; QTM=0100; the export has no days", Optional.empty());

    static final Chain CONTROLM_DAILY_BACKUP = new Chain("CONTROLM_DAILY_BACKUP", Chain.Scheduler.CONTROL_M,
            "DAILY-TransactionBackup", List.of(
            job(LibraryJob.CLOSEFIL), job(LibraryJob.TRANBKP), job(LibraryJob.WAITSTEP), job(LibraryJob.OPENFIL)),
            "Control-M folder DAYS=ALL; TIMETO=23:00", Optional.empty());

    static final Chain CONTROLM_MONTHLY_INTEREST = new Chain("CONTROLM_MONTHLY_INTEREST", Chain.Scheduler.CONTROL_M,
            "MONTHLY-InterestCalculation", List.of(
            job(LibraryJob.CLOSEFIL), job(LibraryJob.INTCALC), job(LibraryJob.COMBTRAN), job(LibraryJob.WAITSTEP),
            job(LibraryJob.OPENFIL)),
            "Control-M folder MONTHLY-InterestCalculation; no day of month; TIMETO=23:00", Optional.empty());

    /** Both Saturday folders wait on MNTTRDB2's condition; TRANEXTR, the other folder's only job, runs no CLOSEFIL. */
    static final Chain CONTROLM_WEEKLY_DISCLOSURE = new Chain("CONTROLM_WEEKLY_DISCLOSURE", Chain.Scheduler.CONTROL_M,
            "WEEKLY-DisclosureGroupsRefresh", List.of(
            new Member.Optional("MNTTRDB2", "Db2"), new Member.Optional("TRANEXTR", "Db2"),
            job(LibraryJob.CLOSEFIL), job(LibraryJob.DISCGRP), job(LibraryJob.WAITSTEP),
            job(LibraryJob.OPENFIL)),
            "Control-M smart folder DAYS=SA after MNTTRDB2, which has no DAYS; TIMETO=23:00",
            Optional.of("0 0 0 * * SAT"));

    private final Map<String, Chain> byName;

    public Chains() {
        this(List.of(CA7_030_POSTING, CA7_030_EXTRACTS, CA7_030_STATEMENTS, CA7_030_REFERENCE, CA7_031_CATBAL,
                CONTROLM_DAILY_BACKUP, CONTROLM_MONTHLY_INTEREST, CONTROLM_WEEKLY_DISCLOSURE));
    }

    private Chains(List<Chain> chains) {
        Map<String, Chain> named = new LinkedHashMap<>();
        for (Chain chain : chains) {
            if (named.putIfAbsent(chain.name(), chain) != null) {
                throw new IllegalArgumentException("two chains are named " + chain.name());
            }
        }
        this.byName = named;
    }

    public static Chains of(Chain... chains) {
        return new Chains(List.of(chains));
    }

    public Optional<Chain> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Collection<Chain> all() {
        return byName.values();
    }

    private static Member job(LibraryJob job) {
        return new Member.Job(job);
    }
}
