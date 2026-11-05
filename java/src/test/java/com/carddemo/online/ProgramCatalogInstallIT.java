package com.carddemo.online;

import java.util.EnumSet;
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
import com.carddemo.authorization.PendingAuthSummaryScreen;
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

    @Autowired
    private PendingAuthSummaryScreen summary;

    private final PendingProgramScreen pending = new PendingProgramScreen();

    @AfterEach
    void installAsMigrated() {
        region.install(EnumSet.allOf(Extension.class));
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
    void bothExtensionScreensArePortedAndInstalled() {
        region.install(Set.of(Extension.TRANSACTION_TYPE_DB2));
        assertTrue(production.installed(LIST));
        assertTrue(production.installed(ProgramName.of("COTRTUPC")));
        assertTrue(production.installed(ADMIN_MENU));
    }

    @Test
    void anUninstalledAuthorizationExtensionHasNoProgramsAndNoTransids() {
        region.install(Set.of());
        assertFalse(production.installed(ProgramName.of("COPAUS0C")));
        assertTrue(production.byTransid(TransId.of("CPVS")).isEmpty());
        assertTrue(production.byTransid(TransId.of("CDRA")).isEmpty());
        assertTrue(production.installed(ProgramName.of("COMEN01C")));
    }

    @Test
    void anInstalledExtensionsProgramWithoutAScreenLandsOnThePendingScreen() {
        region.install(Set.of(Extension.PENDING_AUTHORIZATIONS));
        assertTrue(production.installed(ProgramName.of("COPAUS0C")));
        assertFalse(production.installed(ProgramName.of("COACCT01")));
        assertSame(summary, production.resolve(ProgramName.of("COPAUS0C"), Optional.empty()).screen());
        assertSame(PendingProgramScreen.class, production.resolve(ProgramName.of("COPAUA0C"), Optional.empty()).screen().getClass());
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
