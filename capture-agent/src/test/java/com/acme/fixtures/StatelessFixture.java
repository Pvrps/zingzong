package com.acme.fixtures;

import javax.ejb.Stateless;

@Stateless
public class StatelessFixture {

    public String greet(String name) {
        return "hello " + name;
    }

    public void fail() {
        throw new IllegalStateException("boom");
    }

    public static String ignoredStatic() {
        return "static";
    }

    String ignoredPackagePrivate() {
        return "package";
    }
}
