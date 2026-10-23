package com.acme.fixtures;

/** Not an EJB; must never be instrumented. */
public class PlainFixture {

    public String work() {
        return "plain";
    }
}
