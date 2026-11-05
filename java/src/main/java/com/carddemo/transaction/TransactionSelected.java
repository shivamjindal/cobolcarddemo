package com.carddemo.transaction;

import com.carddemo.online.Handoff;

/**
 * CDEMO-CT01-TRN-SELECTED. Only the transaction list sends it. A menu start has no handoff, so the
 * view opens empty.
 */
public record TransactionSelected(String id) implements Handoff {}
