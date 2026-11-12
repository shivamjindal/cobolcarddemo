package com.carddemo.cards;

import java.io.Serializable;
import java.time.LocalDate;

/** CARD-RECORD (CVACT02Y). */
public record Card(String number, long accountId, int cvv, String embossedName, LocalDate expirationDate,
                   String activeStatus) implements Serializable {
}
