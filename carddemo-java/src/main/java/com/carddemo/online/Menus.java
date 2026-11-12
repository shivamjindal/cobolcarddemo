package com.carddemo.online;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static com.carddemo.online.Program.*;

/** The option tables from app/cpy/COMEN02Y.cpy and app/cpy/COADM02Y.cpy. */
@Configuration
class Menus {

    static final List<MenuOption> MAIN = List.of(
            new MenuOption(1, "Account View", COACTVWC, UserType.USER),
            new MenuOption(2, "Account Update", COACTUPC, UserType.USER),
            new MenuOption(3, "Credit Card List", COCRDLIC, UserType.USER),
            new MenuOption(4, "Credit Card View", COCRDSLC, UserType.USER),
            new MenuOption(5, "Credit Card Update", COCRDUPC, UserType.USER),
            new MenuOption(6, "Transaction List", COTRN00C, UserType.USER),
            new MenuOption(7, "Transaction View", COTRN01C, UserType.USER),
            new MenuOption(8, "Transaction Add", COTRN02C, UserType.USER),
            new MenuOption(9, "Transaction Reports", CORPT00C, UserType.USER),
            new MenuOption(10, "Bill Payment", COBIL00C, UserType.USER),
            new MenuOption(11, "Pending Authorization View", COPAUS0C, UserType.USER));

    static final List<MenuOption> ADMIN = List.of(
            new MenuOption(1, "User List (Security)", COUSR00C, UserType.ADMIN),
            new MenuOption(2, "User Add (Security)", COUSR01C, UserType.ADMIN),
            new MenuOption(3, "User Update (Security)", COUSR02C, UserType.ADMIN),
            new MenuOption(4, "User Delete (Security)", COUSR03C, UserType.ADMIN),
            new MenuOption(5, "Transaction Type List/Update (Db2)", COTRTLIC, UserType.ADMIN),
            new MenuOption(6, "Transaction Type Maintenance (Db2)", COTRTUPC, UserType.ADMIN));

    // Screens is built from every ScreenProgram, menus included, so the menus look it up lazily.
    @Bean
    ScreenProgram mainMenu(ObjectProvider<Screens> screens) {
        return new MenuProgram(COMEN01C, MAIN,
                option -> Message.error("This option " + option.name() + " is not installed..."),
                program -> screens.getObject().installed(program));
    }

    @Bean
    ScreenProgram adminMenu(ObjectProvider<Screens> screens) {
        return new MenuProgram(COADM01C, ADMIN,
                option -> Message.info("This option is not installed ..."),
                program -> screens.getObject().installed(program));
    }
}
