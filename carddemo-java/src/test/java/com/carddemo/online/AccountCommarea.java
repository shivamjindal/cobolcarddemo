package com.carddemo.online;

import java.util.Map;
import java.util.Optional;

/** COACTVWC and COACTUPC COMMAREA fields. COACTVWC keeps no state. */
class AccountCommarea implements CommareaMapping {

    @Override
    public void load(String program, Map<String, Object> commarea, Conversation conversation) {
    }

    @Override
    public Optional<String> read(String name, Conversation conversation) {
        return switch (name) {
            // The account, customer and card COACTVWC found live only on its screen.
            case "CDEMO-ACCT-ID", "CDEMO-CUST-ID", "CDEMO-CUST-FNAME", "CDEMO-CUST-MNAME", "CDEMO-CUST-LNAME",
                 "CDEMO-ACCT-STATUS", "CDEMO-CARD-NUM" -> Optional.empty();
            // Screens are templates, so there is no last map to remember.
            case "CDEMO-LAST-MAPSET", "CDEMO-LAST-MAP" -> Optional.empty();
            default -> throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
        };
    }
}
