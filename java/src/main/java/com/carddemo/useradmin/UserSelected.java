package com.carddemo.useradmin;

import com.carddemo.online.Handoff;
import com.carddemo.users.UserId;

/**
 * CDEMO-CU00-USR-SELECTED, which COUSR02C and COUSR03C read as CDEMO-CU02/CU03-USR-SELECTED at the
 * same COMMAREA offset. Only the user list sends it; any other caller's COMMAREA is shorter, so the
 * field arrives as spaces and the screen starts empty.
 */
public record UserSelected(UserId user) implements Handoff {}
