package com.carddemo.users;

import java.io.Serializable;

/** CDEMO-CU00-USR-SEL-FLG and -USR-SELECTED: the row picked on the list, handed to COUSR02C or COUSR03C. */
public record UserSelection(String userId, Action action) implements Serializable {

    public enum Action { UPDATE, DELETE }
}
