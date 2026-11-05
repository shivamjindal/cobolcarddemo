package com.carddemo.accounts;

/** RESP of a READ UPDATE. */
public sealed interface ForUpdate<T> permits Held, Read.Missing, Read.Failed {}
