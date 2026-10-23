package com.acme.fixtures;

import javax.ejb.Stateless;

/** Loaded only after a recording started, to cover the transform-on-load path. */
@Stateless
public class LateFixture {

    public int twice(int n) {
        return n * 2;
    }
}
