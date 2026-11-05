package com.carddemo.dli;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** IMS database DBPAUTP0, pending authorizations, held in V20's tables. */
@Component
public final class Dbpautp0 {

    private final JdbcClient jdbc;

    public Dbpautp0(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code EXEC DLI SCHD PSB(PSBPAUTB)}: a fresh PCB, positioned before the first root, for this task. */
    public Pcb schedule() {
        return new Pcb(jdbc);
    }
}
