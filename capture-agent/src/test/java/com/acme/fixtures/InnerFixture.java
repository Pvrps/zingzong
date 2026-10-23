package com.acme.fixtures;

import javax.ejb.Stateless;

@Stateless
public class InnerFixture {

    public String work(String s) {
        return s + "!";
    }
}
