package com.carddemo.online;

import java.io.Serializable;

/** CDEMO-USER-ID and CDEMO-USER-TYPE. */
public record SignedOnUser(String id, UserType type) implements Serializable {
}
