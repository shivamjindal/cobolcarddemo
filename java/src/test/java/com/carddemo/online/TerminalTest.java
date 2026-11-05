package com.carddemo.online;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.carddemo.online.maps.CACTUPA;
import com.carddemo.online.maps.CACTVWA;
import com.carddemo.online.maps.COMEN1A;
import com.carddemo.online.maps.COSGN0A;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of characterization/harness/screen.py, which recorded the goldens. */
class TerminalTest {

    @Test
    void eraseResetsEveryFieldToItsBmsDefinition() {
        Terminal terminal = signon();

        assertEquals(Map.of("CURDATE", "mm/dd/yy", "CURTIME", "Ahh:mm:ss", "PASSWD", "________"), terminal.shownValues());
        assertEquals("USERID", terminal.cursor());
        assertEquals(List.of("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "APPLID", "SYSID", "ERRMSG"),
                terminal.protectedFields());
        assertEquals(Color.RED, terminal.colors().get("ERRMSG"));
    }

    @Test
    void lowValuesKeepWhatTheTerminalShowsAndSpacesClearIt() {
        Terminal terminal = signon();
        terminal.typeAndSend(COSGN0A.MAP, Key.ENTER, Map.of("USERID", "USER0001"));

        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP).blank(COSGN0A.PASSWD).set(COSGN0A.ERRMSG, "Wrong"),
                SendMode.CURSOR);

        assertEquals(Map.of("CURDATE", "mm/dd/yy", "CURTIME", "Ahh:mm:ss", "USERID", "USER0001", "ERRMSG", "Wrong"),
                terminal.shownValues());
    }

    @Test
    void lowValuesInsideSentTextShowAsBlanksAndAllLowValuesKeepTheField() {
        Terminal terminal = signon();
        terminal.typeAndSend(COSGN0A.MAP, Key.ENTER, Map.of("USERID", "USER0001"));

        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP).set(COSGN0A.ERRMSG, "-532:\0Fupdate").set(COSGN0A.USERID, "\0\0"),
                SendMode.CURSOR);

        assertEquals("-532: Fupdate", terminal.shownValues().get("ERRMSG"));
        assertEquals("USER0001", terminal.shownValues().get("USERID"));
    }

    @Test
    void darkUnprotectsAFieldWithoutItsTag() {
        Terminal terminal = new Terminal();
        terminal.sendMap(SymbolicMap.lowValues(CACTUPA.MAP).attribute(CACTUPA.FKEY05, FieldAttribute.BMDAR), SendMode.ERASE_CURSOR);

        assertFalse(terminal.protectedFields().contains("FKEY05"));
        assertTrue(terminal.typeAndSend(CACTUPA.MAP, Key.ENTER, Map.of()).map(m -> m.isLowValues(CACTUPA.FKEY05)).orElse(true));
    }

    @Test
    void anotherMapResetsTheScreenWithoutErase() {
        Terminal terminal = signon();
        terminal.typeAndSend(COSGN0A.MAP, Key.ENTER, Map.of("USERID", "USER0001"));

        terminal.sendMap(SymbolicMap.lowValues(COMEN1A.MAP), SendMode.CURSOR);

        assertEquals(Optional.of(COMEN1A.MAP), terminal.map());
        assertFalse(terminal.shownValues().containsKey("USERID"));
    }

    @Test
    void theEarliestCursorRequestInMapOrderWins() {
        Terminal terminal = new Terminal();

        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP).cursor(COSGN0A.PASSWD).cursor(COSGN0A.USERID),
                SendMode.ERASE_CURSOR);

        assertEquals("USERID", terminal.cursor());
    }

    @Test
    void cursorRequestsCountOnlyWithTheCursorOption() {
        Terminal terminal = new Terminal();
        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP).cursor(COSGN0A.PASSWD), SendMode.ERASE_CURSOR);
        assertEquals("PASSWD", terminal.cursor());

        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP), SendMode.CURSOR);
        assertEquals("PASSWD", terminal.cursor(), "no request leaves the cursor where it is");

        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP).cursor(COSGN0A.PASSWD), SendMode.ERASE);
        assertEquals("USERID", terminal.cursor(), "ERASE without CURSOR puts it on the IC field");
    }

    @Test
    void theDefaultColorLeavesTheFieldColor() {
        Terminal terminal = signon();

        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP).color(COSGN0A.ERRMSG, Color.DFCOL), SendMode.CURSOR);
        assertEquals(Color.RED, terminal.colors().get("ERRMSG"));

        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP).color(COSGN0A.ERRMSG, Color.GREEN), SendMode.CURSOR);
        assertEquals(Color.GREEN, terminal.colors().get("ERRMSG"));

        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP), SendMode.ERASE);
        assertEquals(Color.RED, terminal.colors().get("ERRMSG"));
    }

    @Test
    void onlyFieldsWithTheModifiedDataTagAreTransmitted() {
        Terminal terminal = new Terminal();
        terminal.sendMap(SymbolicMap.lowValues(CACTUPA.MAP).set(CACTUPA.ACSTTUS, "Y").set(CACTUPA.OPNYEAR, "2020"),
                SendMode.ERASE);

        SymbolicMap<CACTUPA> received = terminal.typeAndSend(CACTUPA.MAP, Key.ENTER, Map.of("OPNMON", "07")).orElseThrow();

        assertTrue(received.isLowValues(CACTUPA.ACSTTUS), "shown by the program, no FSET, not typed");
        assertEquals("2020", received.text(CACTUPA.OPNYEAR), "FSET");
        assertEquals("07", received.text(CACTUPA.OPNMON), "typed");
    }

    @Test
    void anEmptiedFieldArrivesAsLowValuesAndTrailingBlanksAreDropped() {
        Terminal terminal = signon();

        SymbolicMap<COSGN0A> received = terminal.typeAndSend(COSGN0A.MAP, Key.ENTER,
                Map.of("USERID", "ab  ", "PASSWD", "")).orElseThrow();

        assertTrue(received.isLowValues(COSGN0A.PASSWD));
        assertEquals("ab", received.text(COSGN0A.USERID));
        assertFalse(received.isLowValues(COSGN0A.USERID));
    }

    @Test
    void clearAndPaKeysTransmitNothingButTheTypingStaysOnScreen() {
        Terminal terminal = signon();

        assertTrue(terminal.typeAndSend(COSGN0A.MAP, Key.CLEAR, Map.of("USERID", "X")).isEmpty());
        assertTrue(terminal.typeAndSend(COSGN0A.MAP, Key.PA1, Map.of()).isEmpty());
        assertEquals("X", terminal.shownValues().get("USERID"));
    }

    @Test
    void sendTextReplacesTheMap() {
        Terminal terminal = signon();

        terminal.sendText("Thank you   ");

        assertEquals(Optional.of("Thank you"), terminal.text());
        assertEquals(Optional.empty(), terminal.map());
        assertNull(terminal.cursor());
        assertTrue(terminal.typeAndSend(COSGN0A.MAP, Key.ENTER, Map.of()).isEmpty());
        assertThrows(Terminal.BadInput.class, () -> terminal.typeAndSend(COSGN0A.MAP, Key.ENTER, Map.of("USERID", "x")));
    }

    @Test
    void typingTheTerminalWouldNotAcceptIsRejected() {
        Terminal terminal = signon();

        assertThrows(Terminal.BadInput.class, () -> terminal.typeAndSend(COSGN0A.MAP, Key.ENTER, Map.of("NOPE", "x")));
        assertThrows(Terminal.BadInput.class, () -> terminal.typeAndSend(COSGN0A.MAP, Key.ENTER, Map.of("ERRMSG", "x")));
        assertThrows(Terminal.BadInput.class, () -> terminal.typeAndSend(COSGN0A.MAP, Key.ENTER, Map.of("USERID", "123456789")));
        assertThrows(Terminal.BadInput.class, () -> terminal.typeAndSend(COSGN0A.MAP, Key.ENTER, Map.of("USERID", "a\nb")));
    }

    @Test
    void bmfseUnprotectsAFieldAndSetsItsTag() {
        Terminal terminal = new Terminal();

        terminal.sendMap(SymbolicMap.lowValues(CACTVWA.MAP)
                .set(CACTVWA.ACSTTUS, "Y").attribute(CACTVWA.ACSTTUS, FieldAttribute.BMFSE), SendMode.ERASE);

        assertFalse(terminal.protectedFields().contains("ACSTTUS"));
        SymbolicMap<CACTVWA> received = terminal.typeAndSend(CACTVWA.MAP, Key.ENTER, Map.of()).orElseThrow();
        assertEquals("Y", received.text(CACTVWA.ACSTTUS));
    }

    private static Terminal signon() {
        Terminal terminal = new Terminal();
        terminal.sendMap(SymbolicMap.lowValues(COSGN0A.MAP), SendMode.ERASE);
        return terminal;
    }
}
