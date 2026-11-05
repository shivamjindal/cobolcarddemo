package com.carddemo.authorization;

import com.carddemo.online.Handoff;

/**
 * What COPAUS0C and COPAUS1C pass each other on XCTL: CDEMO-ACCT-ID, CDEMO-CUST-ID and the paging they share.
 *
 * @param acctId {@code null} when CDEMO-ACCT-ID is not numeric
 */
record PendingAuthContext(Long acctId, int custId, AuthorizationPaging paging) implements Handoff {}
