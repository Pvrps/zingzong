package com.acme.orders.web;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.Collections;
import java.util.Set;

@ApplicationPath("api")
public class OrdersApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return new java.util.HashSet<Class<?>>(java.util.Arrays.asList(OrdersResource.class, CartResource.class));
    }
}
