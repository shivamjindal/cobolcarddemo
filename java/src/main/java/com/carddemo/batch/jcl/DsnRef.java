package com.carddemo.batch.jcl;

/** A data set name, or a generation of a GDG relative to the job's view of it ({@code (+1)}, {@code (0)}, {@code (-1)}). */
public sealed interface DsnRef {

    record Named(String dsn) implements DsnRef {}

    record Generation(Gdg gdg, int relative) implements DsnRef {}
}
