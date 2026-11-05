package com.carddemo.db2;

import java.util.Optional;

/** A SELECT INTO or a FETCH: the SQLCA, and the row moved into the host variables when there was one. */
public record Fetched<T>(Sqlca sqlca, Optional<T> row) {}
