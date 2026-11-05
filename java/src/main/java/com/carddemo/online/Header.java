package com.carddemo.online;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

/**
 * POPULATE-HEADER-INFO, which every program runs before each SEND MAP, plus COSGN00C's ASSIGN of
 * APPLID and SYSID. Titles are COTTL01Y.
 */
@Component
final class Header {

    static final String TITLE01 = "      AWS Mainframe Modernization       ";
    static final String TITLE02 = "              CardDemo                  ";
    static final String APPLID = "CARDDEMO";
    static final String SYSID = "CICS";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Clock clock;

    Header(Clock clock) {
        this.clock = clock;
    }

    void fill(SymbolicMap<?> map, ScreenRef<?> screen) {
        LocalDateTime now = LocalDateTime.now(clock);
        map.setIfPresent("TITLE01", TITLE01);
        map.setIfPresent("TITLE02", TITLE02);
        map.setIfPresent("TRNNAME", screen.transid().map(TransId::value).orElse(""));
        map.setIfPresent("PGMNAME", screen.program().value());
        map.setIfPresent("CURDATE", DATE.format(now));
        map.setIfPresent("CURTIME", TIME.format(now));
        map.setIfPresent("APPLID", APPLID);
        map.setIfPresent("SYSID", SYSID);
    }

    /** The message CICS writes to the terminal of a task that abends. */
    String abendMessage(TransId transid, String code) {
        return "DFHAC2206 " + TIME.format(LocalDateTime.now(clock)) + " " + APPLID + " Transaction " + transid.value()
                + " failed with abend " + code + ". Updates to local recoverable resources backed out.";
    }
}
