package com.acme.fixtures;

import javax.ejb.Singleton;

@Singleton
public class SingletonFixture {

    public int count(int n) {
        return n + 1;
    }
}
