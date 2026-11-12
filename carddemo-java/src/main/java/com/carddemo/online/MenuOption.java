package com.carddemo.online;

/** One row of COMEN02Y or COADM02Y; access is CDEMO-MENU-OPT-USRTYPE. */
public record MenuOption(int number, String name, Program target, UserType access) {
}
