package com.carddemo.accounts;

/**
 * The record a READ UPDATE locked until the task ends. Only the stores make one, so a REWRITE cannot
 * be called without the lock CICS requires for it.
 */
public final class Held<T> implements ForUpdate<T> {

    private final T record;

    Held(T record) {
        this.record = record;
    }

    public T record() {
        return record;
    }
}
