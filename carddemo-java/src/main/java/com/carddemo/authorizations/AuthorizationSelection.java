package com.carddemo.authorizations;

import java.io.Serializable;

/** CDEMO-CPVD-PAU-SELECTED with CDEMO-ACCT-ID and CDEMO-CUST-ID: the authorization COPAUS1C shows. */
public record AuthorizationSelection(long accountId, int customerId, AuthorizationKey key) implements Serializable {
}
