package com.acme.fixtures;

import javax.ejb.Stateless;

/** Calls another bean and itself, like OrderService.createOrder and PricingService.price. */
@Stateless
public class OuterFixture {

    private final InnerFixture inner = new InnerFixture();

    public String run(String input) {
        String a = helper(input);
        String b = inner.work(a);
        String c = inner.work(b);
        return helper(c);
    }

    public String helper(String s) {
        return s + "+";
    }

    public void explode() {
        inner.work("x");
        throw new IllegalStateException("outer exploded");
    }
}
