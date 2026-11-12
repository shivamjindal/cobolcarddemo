package com.carddemo.transactions;

import java.io.Serializable;

/** CT00-TRN-SELECTED handed to COTRN01C, which takes it on arrival. */
public record TransactionSelection(String transactionId) implements Serializable {
}
