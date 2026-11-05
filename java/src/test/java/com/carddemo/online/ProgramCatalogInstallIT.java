package com.carddemo.online;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.cics.CicsRegion;
import com.carddemo.cics.Extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CEDA INSTALL of an optional CSD group decides whether its ported programs run. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class ProgramCatalogInstallIT {

    private static final ProgramName LIST = ProgramName.of("COTRTLIC");
    private static final ProgramName ADMIN_MENU = ProgramName.of("COADM01C");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private CicsRegion region;

    @Autowired
    private ProgramCatalog production;

    private final PendingProgramScreen pending = new PendingProgramScreen();

    @AfterEach
    void installAsMigrated() {
        region.install(Set.of(Extension.TRANSACTION_TYPE_DB2));
    }

    @Test
    void anExtensionProgramRunsOnlyWhileItsExtensionIsInstalled() {
        ProgramCatalog catalog = new ProgramCatalog(List.of(new PortedList(), pending), pending, region);

        region.install(Set.of(Extension.TRANSACTION_TYPE_DB2));
        assertEquals(Set.of(Extension.TRANSACTION_TYPE_DB2), region.installed());
        assertTrue(catalog.installed(LIST));
        assertTrue(catalog.byTransid(TransId.of("CTLI")).isPresent());
        assertSame(PortedList.class, catalog.resolve(LIST, Optional.empty()).screen().getClass());

        region.install(Set.of());
        assertEquals(Set.of(), region.installed());
        assertFalse(catalog.installed(LIST));
        assertEquals(Optional.empty(), catalog.byTransid(TransId.of("CTLI")));
        assertSame(pending, catalog.resolve(LIST, Optional.empty()).screen());
    }

    @Test
    void untilTheScreensArePortedTheExtensionProgramsStayPending() {
        region.install(Set.of(Extension.TRANSACTION_TYPE_DB2));
        assertFalse(production.installed(LIST));
        assertFalse(production.installed(ProgramName.of("COTRTUPC")));
        assertTrue(production.installed(ADMIN_MENU));
    }

    /** A stand-in for the COTRTLIC port, so the switch can be seen before the screen exists. */
    private static final class PortedList extends Screen<NoMap, PortedList.Action, NoState, NoHandoff> {

        enum Action implements ScreenAction {
            BACK(Key.PF3);

            private final Key key;

            Action(Key key) {
                this.key = key;
            }

            @Override
            public Key key() {
                return key;
            }
        }

        PortedList() {
            super(ScreenRef.of("COTRTLIC", "CTLI", "tran-type-list", NoHandoff.class), NoMap.MAP, Action.class,
                    NoState.class, Access.SIGNED_ON);
        }

        @Override
        protected Outcome enter(Arrival<NoHandoff> arrival) {
            return showText("COTRTLIC", NoState.INSTANCE);
        }

        @Override
        protected Outcome act(Action action, SymbolicMap<NoMap> map, NoState state) {
            return showText("COTRTLIC", state);
        }

        @Override
        protected Outcome invalidKey(SymbolicMap<NoMap> map, NoState state) {
            return showText("COTRTLIC", state);
        }
    }
}
