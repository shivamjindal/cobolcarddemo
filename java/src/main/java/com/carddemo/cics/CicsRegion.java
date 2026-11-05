package com.carddemo.cics;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Region CICSAWSA's open and closed files and its installed {@link Extension}s, kept in row {@code cics_region}. An online task reads the row FOR SHARE
 * on each file request and holds that lock until it commits, so CEMT SET FILE, an UPDATE of the row, waits for
 * every task using the region's files to end, as CEMT waits for in-flight tasks before it closes a file. Batch
 * programs never ask: CLOSEFIL is what keeps them apart from the online tasks.
 */
@Component
public final class CicsRegion {

    public static final String APPLID = "CICSAWSA";

    private final JdbcClient jdbc;

    public CicsRegion(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether the running task may use {@code file}; a closed file answers every request with RESP NOTOPEN. */
    public boolean isOpen(CicsFile file) {
        return !read(" for share").contains(file);
    }

    /** {@code CEMT SET FILE(file) CLOSED}. */
    public void close(CicsFile file) {
        jdbc.sql("update cics_region set closed = array_append(array_remove(closed, ?), ?)")
                .params(file.name(), file.name()).update();
    }

    /** {@code CEMT SET FILE(file) OPEN}. */
    public void open(CicsFile file) {
        jdbc.sql("update cics_region set closed = array_remove(closed, ?)").param(file.name()).update();
    }

    /** {@code CEMT INQUIRE FILE}: the files that are closed now. */
    public Set<CicsFile> closed() {
        return read("");
    }

    /** The optional CSD groups installed now. */
    public Set<Extension> installed() {
        String[] ids = jdbc.sql("select installed from cics_region")
                .query((rs, n) -> (String[]) rs.getArray(1).getArray()).single();
        Set<Extension> installed = EnumSet.noneOf(Extension.class);
        Arrays.stream(ids).map(Extension::byId).forEach(installed::add);
        return installed;
    }

    /** {@code CEDA INSTALL} of exactly these groups; any other optional group is discarded. */
    public void install(Set<Extension> extensions) {
        jdbc.sql("update cics_region set installed = ?")
                .param(extensions.stream().map(Extension::id).sorted().toArray(String[]::new)).update();
    }

    private Set<CicsFile> read(String lock) {
        String[] names = jdbc.sql("select closed from cics_region" + lock)
                .query((rs, n) -> (String[]) rs.getArray(1).getArray()).single();
        Set<CicsFile> closed = EnumSet.noneOf(CicsFile.class);
        Arrays.stream(names).map(CicsFile::valueOf).forEach(closed::add);
        return closed;
    }
}
